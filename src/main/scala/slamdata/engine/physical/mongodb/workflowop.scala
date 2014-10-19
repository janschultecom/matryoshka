package slamdata.engine.physical.mongodb

import collection.immutable.ListMap

import slamdata.engine.fs.Path
import slamdata.engine.{Error, RenderTree, Terminal, NonTerminal}
import slamdata.engine.fp._
import optimize.pipeline._
import WorkflowTask._

import scalaz._
import Scalaz._
import monocle.Macro._
import monocle.syntax._

// FIXME: FIXPOINT all the things!

/**
  A WorkflowOp is basically an atomic operation, with references to its inputs.
  After generating a tree of these (actually, a graph, but we’ll get to that),
  we crush them down into a Workflow. This `crush` gives us a location to
  optimize our workflow decisions. EG, A sequence of simple ops may be combined
  into a single pipeline request, but if one of those operations contains JS, we
  have to execute that outside of a pipeline, possibly reordering the other
  operations to avoid having two pipelines with a JS operation in the middle.
 
  We also implement the optimizations at
  http://docs.mongodb.org/manual/core/aggregation-pipeline-optimization/ so that
  we can build others potentially on top of them (including reordering
  non-pipelines around pipelines, etc.).
  */
sealed trait WorkflowOp {
  import ExprOp.DocVar
  import WorkflowBuilder.ExprVar

  def srcs: List[WorkflowOp]

  /**
    Returns both the final WorkflowTask as well as a DocVar indicating the base
    of the collection.
    */
  def crush: (DocVar, WorkflowTask)

  def finish: WorkflowOp = this.deleteUnusedFields(Set.empty)

  def workflow: WorkflowTask = WorkflowOp.finalize(this.finish).crush._2

  def vertices: List[WorkflowOp] = this :: srcs.flatMap(_.vertices)

  import ExprOp.{GroupOp => _, _}
  import PipelineOp._
  import WorkflowOp._

  def rewriteRefs(applyVar0: PartialFunction[DocVar, DocVar]): this.type = {
    val applyVar = (f: DocVar) => applyVar0.lift(f).getOrElse(f)

    def applyExprOp(e: ExprOp): ExprOp = e.mapUp {
      case f : DocVar => applyVar(f)
    }

    def applyFieldName(name: BsonField): BsonField = {
      applyVar(DocField(name)).deref.getOrElse(name) // TODO: Delete field if it's transformed away to nothing???
    }

    def applySelector(s: Selector): Selector = s.mapUpFields(PartialFunction(applyFieldName _))

    def applyReshape(shape: Reshape): Reshape = shape match {
      case Reshape.Doc(value) => Reshape.Doc(value.transform {
        case (k, -\/(e)) => -\/(applyExprOp(e))
        case (k, \/-(r)) => \/-(applyReshape(r))
      })

      case Reshape.Arr(value) => Reshape.Arr(value.transform {
        case (k, -\/(e)) => -\/(applyExprOp(e))
        case (k, \/-(r)) => \/-(applyReshape(r))
      })
    }

    def applyGrouped(grouped: Grouped): Grouped = Grouped(grouped.value.transform {
      case (k, groupOp) => applyExprOp(groupOp) match {
        case groupOp : ExprOp.GroupOp => groupOp
        case _ => sys.error("Transformation changed the type -- error!")
      }
    })

    def applyMap[A](m: ListMap[BsonField, A]): ListMap[BsonField, A] = m.map(t => applyFieldName(t._1) -> t._2)

    def applyNel[A](m: NonEmptyList[(BsonField, A)]): NonEmptyList[(BsonField, A)] = m.map(t => applyFieldName(t._1) -> t._2)

    def applyFindQuery(q: FindQuery): FindQuery = {
      q.copy(
        query   = applySelector(q.query),
        max     = q.max.map(applyMap _),
        min     = q.min.map(applyMap _),
        orderby = q.orderby.map(applyNel _)
      )
    }

    (this match {
      case ProjectOp(src, shape)     => chain(src, projectOp(applyReshape(shape)))
      case GroupOp(src, grouped, by) =>
        chain(src,
          groupOp(applyGrouped(grouped), by.bimap(applyExprOp _, applyReshape _)))
      case MatchOp(src, s)           => chain(src, matchOp(applySelector(s)))
      case RedactOp(src, e)          => chain(src, redactOp(applyExprOp(e)))
      case v @ LimitOp(src, _)       => v
      case v @ SkipOp(src, _)        => v
      case v @ UnwindOp(src, f)      => chain(src, unwindOp(applyVar(f)))
      case v @ SortOp(src, l)        => chain(src, sortOp(applyNel(l)))
      // case v @ OutOp(src, _)         => v
      case g : GeoNearOp             =>
        g.copy(
          distanceField = applyFieldName(g.distanceField),
          query = g.query.map(applyFindQuery _))
      case v                       => v
    }).asInstanceOf[this.type]
  }
  
  final def refs: List[DocVar] = {
    // FIXME: Sorry world
    val vf = new scala.collection.mutable.ListBuffer[DocVar]

    rewriteRefs {
      case v => vf += v; v
    }

    vf.toList
  }

  def collectShapes: (List[PipelineOp.Reshape], WorkflowOp) = this match {
    case ProjectOp(src, shape) =>
      Arrow[Function1].first((x: List[PipelineOp.Reshape]) => shape :: x)(src.collectShapes)
    case _                     => (Nil, this)
  }

  def map(f: WorkflowOp => WorkflowOp): WorkflowOp = this match {
    case _: SourceOp             => this
    case p: SingleSourceOp       => p.reparent(f(p.src))
    case FoldLeftOp(head, tail)  => FoldLeftOp.make(f(head), tail.map(f))
    case JoinOp(srcs)            => JoinOp.make(srcs.map(f))
    // case OutOp(src, dst)        => OutOp.make(f(src), dst)
  }

  def deleteUnusedFields(usedRefs: Set[DocVar]): WorkflowOp = {
    def getRefs[A](op: WorkflowOp, prev: Set[DocVar]): Set[DocVar] =
      (op match {
        // Don't count unwinds (if the var isn't referenced elsewhere, it's effectively unused)
        case UnwindOp(_, _) => prev
        case WorkflowOp.GroupOp(_, _, _) => op.refs
        // FIXME: Since we can’t reliably identify which fields are used by a JS
        //        function, we need to assume they all are, until we hit the
        //        next GroupOp or ProjectOp.
        case MapOp(_, _) => Nil
        case FlatMapOp(_, _) => Nil
        case ReduceOp(_, _) => Nil
        case ProjectOp(_, _) => op.refs
        case _ => prev ++ op.refs
      }).toSet

    def unused(defs: Set[DocVar], refs: Set[DocVar]): Set[DocVar] =
      defs.filterNot(d => refs.exists(ref => d.startsWith(ref) || ref.startsWith(d)))

    def getDefs(op: WorkflowOp): Set[DocVar] = (op match {
      case p @ ProjectOp(_, _) => p.getAll.map(_._1)
      case g @ GroupOp(_, _, _) => g.getAll.map(_._1)
      case _ => Nil
    }).map(DocVar.ROOT(_)).toSet

    val pruned = if (!usedRefs.isEmpty) {
      val unusedRefs =
        unused(getDefs(this), usedRefs).toList.flatMap(_.deref.toList)
      this match {
        case p @ ProjectOp(_, _) => p.deleteAll(unusedRefs)
        case g @ GroupOp(_, _, _) => g.deleteAll(unusedRefs.map(_.flatten.head))
        case _ => this
      }
    }
      else this
    pruned.map(_.deleteUnusedFields(getRefs(pruned, usedRefs)))
  }
  
  def merge(that: WorkflowOp): ((DocVar, DocVar), WorkflowOp) = {
    import WorkflowBuilder.{ExprVar, ExprName, LeftLabel, LeftVar, LeftName, RightLabel, RightVar, RightName}
    
    def delegate = {
      val ((r, l), merged) = that merge this
      ((l, r), merged)
    }
    
    if (this == that)
      ((DocVar.ROOT(), DocVar.ROOT()) -> that)
    else
      (this, that) match {
        case (PureOp(lbson), PureOp(rbson)) =>
          ((LeftVar, RightVar) ->
            pureOp(Bson.Doc(ListMap(
              LeftLabel -> lbson,
              RightLabel -> rbson))))
        case (PureOp(bson), r) =>
          ((LeftVar, RightVar) ->
            chain(
              r,
              projectOp(Reshape.Doc(ListMap(
                LeftName -> -\/(ExprOp.Literal(bson)),
                RightName -> -\/(DocVar.ROOT()))))))
        case (_, PureOp(_)) => delegate

        case (left : GeoNearOp, r : WPipelineOp) =>
          val ((lb, rb), src) = left merge r.src
          val (left0, lb0) = rewrite(left, lb)
          val (right0, rb0) = rewrite(r, rb)
          ((lb0, rb), right0.reparent(src))
        case (_, _ : GeoNearOp) => delegate

        case (left @ ProjectOp(lsrc, lshape), right) if lsrc == right =>
          ((LeftVar, RightVar) ->
            chain(lsrc,
              projectOp(
                Reshape.Doc(ListMap(
                  LeftName -> \/- (lshape),
                  RightName -> -\/ (ExprOp.DocVar.ROOT()))))))
        case (left, ProjectOp(rsrc, _)) if left == rsrc => delegate

        case (left: WorkflowOp.ShapePreservingOp, r: WPipelineOp) =>
          val ((lb, rb), src) = left merge r.src
          val (left0, lb0) = rewrite(left, lb)
          val (right0, rb0) = rewrite(r, rb)
          ((lb0, rb), right0.reparent(src))
        case (_: WPipelineOp, _: WorkflowOp.ShapePreservingOp) => delegate

        case (left @ ProjectOp(lsrc, shape), r: SourceOp) =>
          ((LeftVar, RightVar) ->
            chain(lsrc,
              projectOp(Reshape.Doc(ListMap(
                LeftName -> \/- (shape),
                RightName -> -\/ (DocVar.ROOT()))))))
        case (_: SourceOp, ProjectOp(_, _)) => delegate

        case (left @ UnwindOp(lsrc, lfield), right @ GroupOp(_, _, _)) =>
          val ((lb, rb), src) = lsrc merge right
          ((lb, rb) ->
            chain(src,
              unwindOp(lb \\ lfield)))
        case (_: GroupOp, _: UnwindOp) => delegate

        case (left @ GroupOp(lsrc, Grouped(_), b1), right @ GroupOp(rsrc, Grouped(_), b2)) if (b1 == b2) =>
          val ((lb, rb), src) = lsrc merge rsrc
          val (GroupOp(_, Grouped(g1_), _), lb0) = rewrite(left, lb)
          val (GroupOp(_, Grouped(g2_), _), rb0) = rewrite(right, rb)

          // Rewrite:
          // - each grouped value is given a new temp name in the merged GroupOp.
          // - a ProjectOp is added after grouping to rearrange the values
          //   under lEft and rIght.
          // This is needed because GroupOp cannot create nested structure, and
          // we need the value from each original op to be located under a single
          // name (lEft/rIght).
          val oldNames: List[BsonField.Leaf] = g1_.keys.toList ++ g2_.keys.toList
          val ops = g1_.values.toList ++ g2_.values.toList
          val tempNames = BsonField.genUniqNames(ops.length, Nil): List[BsonField.Leaf]

          // New grouped values:
          val g = ListMap((tempNames zip ops): _*)

          // Project from flat temps to lEft/rIght:
          val (ot1, ot2) = (oldNames zip tempNames).splitAt(g1_.length)
          val t = (LeftName -> ot1) :: (RightName -> ot2) :: Nil 
          val s: ListMap[BsonField.Name, ExprOp \/ Reshape] = ListMap(
            t.map { case (n, ot) =>
              n -> \/- (Reshape.Doc(ListMap(
                ot.map { case (old, tmp) => old.toName -> -\/ (ExprOp.DocField(tmp)) }: _*)))
            }: _*)
          
          ((LeftVar, RightVar) ->
            chain(src,
              groupOp(Grouped(g), b1),
              projectOp(Reshape.Doc(s))))

        case (left @ GroupOp(_, Grouped(_), _), r: WPipelineOp) =>
          val ((lb, rb), src) = left.src merge r
          val (GroupOp(_, Grouped(g1_), b1), lb0) = rewrite(left, lb)
          val uniqName = BsonField.genUniqName(g1_.keys.map(_.toName))
          val uniqVar = DocVar.ROOT(uniqName)

          ((lb0, uniqVar) ->
            chain(src,
              groupOp(Grouped(g1_ + (uniqName -> ExprOp.Push(rb))), b1),
              unwindOp(uniqVar)))
        case (_: WPipelineOp, GroupOp(_, _, _)) => delegate

        case (left @ ProjectOp(lsrc, _), right @ ProjectOp(rsrc, _)) =>
          val ((lb, rb), src) = lsrc merge rsrc
          val (left0, lb0) = rewrite(left, lb)
          val (right0, rb0) = rewrite(right, rb)
          Reshape.merge(left0.shape, right0.shape) match {
            case Some(merged) => ((lb0, rb0) -> chain(src, projectOp(merged)))
            case None         =>
              ((LeftVar \\ lb0, RightVar \\ rb0) ->
                chain(src,
                  projectOp(Reshape.Doc(ListMap(
                    LeftName -> \/-(left0.shape),
                    RightName -> \/-(right0.shape))))))}

        case (left @ ProjectOp(lsrc, _), r: WPipelineOp) =>
          val ((lb, rb), op) = lsrc merge r
          val (left0, lb0) = rewrite(left, lb)
          ((LeftVar \\ lb0, RightVar \\ rb) ->
            chain(op,
              projectOp(Reshape.Doc(ListMap(
                LeftName -> \/- (left0.shape),
                RightName -> -\/ (DocVar.ROOT()))))))
        case (_: WPipelineOp, ProjectOp(_, _)) => delegate

        case (left @ RedactOp(lsrc, _), right @ RedactOp(rsrc, _)) =>
          val ((lb, rb), src) = lsrc merge rsrc
          val (left0, lb0) = rewrite(left, lb)
          val (right0, rb0) = rewrite(right, rb)
          ((lb0, rb0) -> chain(src,
            redactOp(left0.value),
            redactOp(right0.value)))

        case (left @ UnwindOp(lsrc, lfield), right @ UnwindOp(rsrc, rfield)) if lfield == rfield =>
          val ((lb, rb), src) = lsrc merge rsrc
          val (left0, lb0) = rewrite(left, lb)
          val (right0, rb0) = rewrite(right, rb)
          ((lb0, rb0) -> chain(src, 
            unwindOp(left0.field)))

        case (left @ UnwindOp(lsrc, _), right @ UnwindOp(rsrc, _)) =>
          val ((lb, rb), src) = lsrc merge rsrc
          val (left0, lb0) = rewrite(left, lb)
          val (right0, rb0) = rewrite(right, rb)
          ((lb0, rb0) -> chain(src,
            unwindOp(left0.field),
            unwindOp(right0.field)))

        case (left @ UnwindOp(lsrc, lfield), right @ RedactOp(_, _)) =>
          val ((lb, rb), src) = lsrc merge right
          val (left0, lb0) = rewrite(left, lb)
          val (right0, rb0) = rewrite(right, rb)
          ((lb0, rb0) -> left0.reparent(src))
        case (RedactOp(_, _), UnwindOp(_, _)) => delegate

        case (l @ ReadOp(_), MapOp(rsrc, fn)) =>
          val ((lb, rb), src) = l merge rsrc
          ((LeftVar \\ lb, RightVar) ->
            // TODO: we’re using src in 2 places here. Need #347’s `ForkOp`.
            foldLeftOp(
              chain(src,
                projectOp(Reshape.Doc(ListMap(
                    LeftName -> -\/(DocVar.ROOT()))))),
              chain(src,
                projectOp(
                  Reshape.Doc(ListMap(ExprName -> -\/(rb \\ ExprVar)))),
                mapOp(fn),
                projectOp(Reshape.Doc(ListMap(
                  RightName -> -\/(DocVar.ROOT())))))))
        case (MapOp(_, _), ReadOp(_)) => delegate

        case (left @ MapOp(_, _), r @ ProjectOp(rsrc, shape)) =>
          val ((lb, rb), src) = left merge rsrc
          val (left0, lb0) = rewrite(left, lb)
          val (right0, rb0) = rewrite(r, rb)
          ((LeftVar \\ lb0, RightVar \\ rb) ->
            chain(src,
              projectOp(Reshape.Doc(ListMap(
                LeftName -> -\/(DocVar.ROOT()),
                RightName -> \/-(shape))))))
        case (ProjectOp(_, _), MapOp(_, _)) => delegate

        case (left: WorkflowOp, right: WPipelineOp) =>
          val ((lb, rb), src) = left merge right.src
          val (left0, lb0) = rewrite(left, lb)
          val (right0, rb0) = rewrite(right, rb)
          ((lb0, rb0) -> right0.reparent(src))
        case (_: WPipelineOp, _: WorkflowOp) => delegate

        case (l, r) =>
          ((LeftVar, RightVar) ->
            foldLeftOp(
              chain(l,
                projectOp(Reshape.Doc(ListMap(
                  LeftName -> -\/(DocVar.ROOT()))))),
              chain(r,
                projectOp(Reshape.Doc(ListMap(
                    RightName -> -\/(DocVar.ROOT())))))))
      }
  }
}

object WorkflowOp {
  import ExprOp.DocVar

  private val ExprLabel  = "value"
  private val ExprName   = BsonField.Name(ExprLabel)
  private val ExprVar    = ExprOp.DocVar.ROOT(ExprName)

  private val LeftLabel  = "lEft"
  private val LeftName   = BsonField.Name(LeftLabel)
  private val LeftVar    = ExprOp.DocVar.ROOT(LeftName)

  private val RightLabel = "rIght"
  private val RightName  = BsonField.Name(RightLabel)
  private val RightVar   = ExprOp.DocVar.ROOT(RightName)

  def rewrite[A <: WorkflowOp](op: A, base: ExprOp.DocVar): (A, ExprOp.DocVar) =
    (op.rewriteRefs(PartialFunction(base \\ _)) -> (op match {
      case GroupOp(_, _, _) => ExprOp.DocVar.ROOT()
      case ProjectOp(_, _)  => ExprOp.DocVar.ROOT()
      case _                => base
    }))

  /**
   * Operations without an input.
   */
  sealed trait SourceOp extends WorkflowOp {
    def srcs = Nil // Set.empty
  }

  /** Operations with a single source op. */
  sealed trait SingleSourceOp extends WorkflowOp {
    def src: WorkflowOp
    def reparent(newSrc: WorkflowOp): SingleSourceOp
  
    def srcs = List(src) // Set(src)
  }

  /**
   * This should be renamed once the other PipelineOp goes away, but it is the
   * subset of operations that can ever be pipelined.
   */
  sealed trait WPipelineOp extends SingleSourceOp {
    def pipeline: Option[(DocVar, WorkflowTask, List[PipelineOp])]
  }
  sealed trait ShapePreservingOp extends WPipelineOp

  /**
   * Flattens the sequence of operations like so:
   * 
   *   chain(
   *     readOp(Path.fileAbs("foo")),
   *     matchOp(Selector.Where(Js.Bool(true))),
   *     limitOp(7))
   * ==
   *   val read = readOp(Path.fileAbs("foo"))
   *   val match = matchOp(Selector.Where(Js.Bool(true))(read)
   *   limitOp(7)(match)
   */
  def chain[A <: SingleSourceOp](src: WorkflowOp, op1: WorkflowOp => A, ops: (WorkflowOp => A)*): A =
    ops.foldLeft(op1(src))((s, o) => o(s))

  /**
    Performs some irreversible conversions, meant to be used once, after the
    entire workflow has been generated.
    */
  // probable conversions
  // to MapOp:          ProjectOp
  // to FlatMapOp:      MatchOp, LimitOp (using scope), SkipOp (using scope), UnwindOp, GeoNearOp
  // to MapOp/ReduceOp: GroupOp
  // ???:               RedactOp
  // none:              SortOp
  // NB: We don’t convert a ProjectOp after a map/reduce op because it could
  //     affect the final shape unnecessarily.
  def finalize(op: WorkflowOp): WorkflowOp = op match {
    case FlatMapOp(ProjectOp(src, shape), fn) =>
      shape.toJs(Js.Ident("value")).fold(op.map(finalize(_)))(
        x => finalize(chain(
          src,
          mapOp(MapOp.mapMap("value", x)),
          flatMapOp(fn))))
    case FlatMapOp(uw @ UnwindOp(src, _), fn) =>
      finalize(chain(src, flatMapOp(uw.flatmapop), flatMapOp(fn)))
    case MapOp(ProjectOp(src, shape), fn) =>
      shape.toJs(Js.Ident("value")).fold(op.map(finalize(_)))(
        x => finalize(chain(
          src,
          mapOp(MapOp.mapMap("value", x)),
          mapOp(fn))))
    case MapOp(uw @ UnwindOp(src, _), fn) =>
      finalize(chain(src, flatMapOp(uw.flatmapop), mapOp(fn)))
    case ReduceOp(ProjectOp(src, shape), fn) =>
      shape.toJs(Js.Ident("value")).fold(op.map(finalize(_)))(
        x => finalize(chain(
          src,
          mapOp(MapOp.mapMap("value", x)),
          reduceOp(fn))))
    case ReduceOp(uw @ UnwindOp(src, _), fn) =>
      finalize(chain(src, flatMapOp(uw.flatmapop), reduceOp(fn)))
    case op @ FoldLeftOp(head, tail) =>
      foldLeftOp(
        finalize(chain(
          head,
          projectOp(PipelineOp.Reshape.Doc(ListMap(
            ExprName -> -\/(ExprOp.DocVar.ROOT())))))),
        finalize(tail.head match {
          case op @ ReduceOp(_, _) => op
          case op => chain(op, reduceOp(ReduceOp.reduceFoldLeft))
        }),
        tail.tail.map(x => finalize(x match {
          case op @ ReduceOp(_, _) => op
          case op => chain(op, reduceOp(ReduceOp.reduceFoldLeft))
        })):_*)
    case op => op.map(finalize(_))
  }

  case class PureOp(value: Bson) extends SourceOp {
    def crush = (DocVar.ROOT(),  PureTask(value))
  }
  val pureOp = PureOp.apply _

  case class ReadOp(coll: Collection) extends SourceOp {
    def crush = (DocVar.ROOT(), ReadTask(coll))
  }
  val readOp = ReadOp.apply _

  case class MatchOp private (src: WorkflowOp, selector: Selector) extends ShapePreservingOp {
    private def coalesce: ShapePreservingOp = src match {
      case SortOp(src0, value) => chain(src0, matchOp(selector), sortOp(value))
      case MatchOp(src0, sel0) => chain(src0, matchOp(Semigroup[Selector].append(sel0, selector)))
      case _ => this
    }
    def crush = {
      // TODO: If we ever allow explicit request of cursors (instead of
      //       collections), we could generate a FindQuery here.
      lazy val nonPipeline = {
        val (base, crushed) = src.crush
        (ExprVar,
          MapReduceTask(
            crushed,
            MapReduce(
              MapOp.mapFn(base match {
                case DocVar(DocVar.ROOT, None) => MapOp.mapNOP
                case _ => MapOp.mapProject(base)
              }),
              ReduceOp.reduceNOP,
              selection = Some(PipelineOp.Match(selector).rewriteRefs(PartialFunction(base \\ _)).selector))))
      }
      pipeline match {
        case Some((base, up, mine)) =>
          (base, PipelineTask(up, Pipeline(mine)))
        case None             => nonPipeline
      }
    }

    def pipeline = {
      def pipelinable(sel: Selector): Boolean = sel match {
        case Selector.Where(_) => false
        case comp: Selector.CompoundSelector =>
          pipelinable(comp.left) && pipelinable(comp.right)
        case _ => true
      }

      if (pipelinable(selector)) {
        lazy val (base, crushed) = src.crush
        val op = PipelineOp.Match(selector)
        src match {
          case p: WPipelineOp => p.pipeline.cata(
            { case (base, up, prev) => Some((base, up, prev :+ op.rewriteRefs(PartialFunction(base \\ _)))) },

            Some((base, crushed, List(op.rewriteRefs(PartialFunction(base \\ _))))))
          case _ => Some((base, crushed, List(op.rewriteRefs(PartialFunction(base \\ _)))))
        }
      }
      else None
    }
    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)
  }
  object MatchOp {
    def make(selector: Selector)(src: WorkflowOp): ShapePreservingOp = MatchOp(src, selector).coalesce
  }
  val matchOp = MatchOp.make _

  private def alwaysPipePipe(src: WorkflowOp, op: PipelineOp) = {
    lazy val (base, crushed) = src.crush
    // TODO: this is duplicated in `WorkflowBuilder.rewrite`
    def repairBase(base: DocVar) = op match {
      case PipelineOp.Group(_, _) => DocVar.ROOT()
      case PipelineOp.Project(_)  => DocVar.ROOT()
      case _                      => base
    }
    src match {
      case p: WPipelineOp => p.pipeline.cata(
        {
          case (base, up, prev) =>
            (repairBase(base),
              up,
              prev :+ op.rewriteRefs(PartialFunction(base \\ _)))
        },
        (repairBase(base),
          crushed,
          List(op.rewriteRefs(PartialFunction(base \\ _)))))
      case _ =>
        (repairBase(base),
          crushed,
          List(op.rewriteRefs(PartialFunction(base \\ _))))
    }
  }

  private def alwaysCrushPipe(src: WorkflowOp, op: PipelineOp) =
    alwaysPipePipe(src, op) match {
      case (base, up, pipe) => (base, PipelineTask(up, Pipeline(pipe)))
    }

  case class ProjectOp private (src: WorkflowOp, shape: PipelineOp.Reshape)
      extends WPipelineOp {

    import PipelineOp._

    private def pipeop = PipelineOp.Project(shape)
    private def coalesce = src match {
      case ProjectOp(_, _) =>
        val (rs, src) = this.collectShapes
        inlineProject(rs.head, rs.tail).map(projectOp(_)(src)).getOrElse(this)

      case GroupOp(src, grouped, by) =>
        inlineProjectGroup(shape, grouped).map(groupOp(_, by)(src)).getOrElse(this)

      case UnwindOp(GroupOp(src, grouped, by), unwound) =>
        inlineProjectUnwindGroup(shape, unwound, grouped).map { case (unwound, grouped) => 
          chain(src,
            groupOp(grouped, by),
            unwindOp(unwound))
          }.getOrElse(this)

      case _ => this
    }
    def crush = alwaysCrushPipe(src, pipeop)
    def pipeline = Some(alwaysPipePipe(src, pipeop))
    def reparent(newSrc: WorkflowOp): ProjectOp = copy(src = newSrc)

    def empty: ProjectOp = shape match {
      case Reshape.Doc(_) => ProjectOp.EmptyDoc(src)
      case Reshape.Arr(_) => ProjectOp.EmptyArr(src)
    }

    def set(field: BsonField, value: ExprOp \/ Reshape): WPipelineOp =
      chain(src, projectOp(shape.set(field, value)))

    def getAll: List[(BsonField, ExprOp)] = Reshape.getAll(shape)

    def setAll(fvs: Iterable[(BsonField, ExprOp \/ Reshape)]): WPipelineOp =
      chain(src, projectOp(Reshape.setAll(shape, fvs)))

    def deleteAll(fields: List[BsonField]): WPipelineOp = {
      empty.setAll(getAll.filterNot(t => fields.exists(t._1.startsWith(_))).map(t => t._1 -> -\/ (t._2)))
    }
  }
  object ProjectOp {
    import PipelineOp._

    def make(shape: Reshape)(src: WorkflowOp): WPipelineOp = ProjectOp(src, shape).coalesce
    def uncoalesced(shape: Reshape)(src: WorkflowOp): ProjectOp = ProjectOp(src, shape)

    val EmptyDoc = (src: WorkflowOp) => ProjectOp(src, Reshape.EmptyDoc)
    val EmptyArr = (src: WorkflowOp) => ProjectOp(src, Reshape.EmptyArr)   
  }
  val projectOp = ProjectOp.make _

  case class RedactOp private (src: WorkflowOp, value: ExprOp) extends WPipelineOp {
    private def pipeop = PipelineOp.Redact(value)
    def crush = alwaysCrushPipe(src, pipeop)
    def pipeline = Some(alwaysPipePipe(src, pipeop))
    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)
  }
  object RedactOp {
    def make(value: ExprOp)(src: WorkflowOp): RedactOp = RedactOp(src, value)
  }
  val redactOp = RedactOp.make _

  case class LimitOp private (src: WorkflowOp, count: Long) extends ShapePreservingOp {
    import MapReduce._

    private def pipeop = PipelineOp.Limit(count)
    private def coalesce = src match {
      case LimitOp(src0, count0) =>
        chain(src0,
          limitOp(Math.min(count0, count)))
      case SkipOp(src0, count0) =>
        chain(src0,
          limitOp(count0 + count),
          skipOp(count0))
      case _ => this
    }
    // TODO: If the preceding is a MatchOp, and it or its source isn’t
    //       pipelineable, then return a FindQuery combining the match and this
    //       limit
    def crush = alwaysCrushPipe(src, pipeop)
    def pipeline = Some(alwaysPipePipe(src, pipeop))
    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)
  }
  object LimitOp {
    def make(count: Long)(src: WorkflowOp): ShapePreservingOp = LimitOp(src, count).coalesce
  }
  val limitOp = LimitOp.make _

  case class SkipOp private (src: WorkflowOp, count: Long) extends ShapePreservingOp {
    private def pipeop = PipelineOp.Skip(count)
    private def coalesce: SkipOp = src match {
      case SkipOp(src0, count0) => SkipOp(src0, count0 + count).coalesce
      case _                    => this
    }
    // TODO: If the preceding is a MatchOp (or a limit preceded by a MatchOp),
    //       and it or its source isn’t pipelineable, then return a FindQuery
    //       combining the match and this skip
    def crush = alwaysCrushPipe(src, pipeop)
    def pipeline = Some(alwaysPipePipe(src, pipeop))
    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)
  }
  object SkipOp {
    def make(count: Long)(src: WorkflowOp): ShapePreservingOp = SkipOp(src, count).coalesce
  }
  val skipOp = SkipOp.make _

  case class UnwindOp private (src: WorkflowOp, field: ExprOp.DocVar)
      extends WPipelineOp {
    private def pipeop = PipelineOp.Unwind(field)
    lazy val flatmapop = {
      Js.AnonFunDecl(List("key", "value"),
        List(
          Js.VarDef(List("each" -> Js.AnonObjDecl(Nil))),
          ReduceOp.copyAllFields(Js.Ident("value"))(Js.Ident("each")),
          Js.Return(
            Js.Call(Js.Select(field.toJs(Js.Ident("value")), "map"), List(
              Js.AnonFunDecl(List("elem"), List(
                Js.BinOp("=", field.toJs(Js.Ident("each")), Js.Ident("elem")),
                Js.Return(
                  Js.AnonElem(List(
                    Js.Call(Js.Ident("ObjectId"), Nil),
                    Js.Ident("each")))))))))))
    }
    def crush = alwaysCrushPipe(src, pipeop)
    def pipeline = Some(alwaysPipePipe(src, pipeop))
    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)
  }
  object UnwindOp {
    def make(field: ExprOp.DocVar)(src: WorkflowOp): UnwindOp = UnwindOp(src, field)
  }
  val unwindOp = UnwindOp.make _
  
  case class GroupOp private (
    src: WorkflowOp,
    grouped: PipelineOp.Grouped,
    by: ExprOp \/ PipelineOp.Reshape)
      extends WPipelineOp {

    import PipelineOp._

    // TODO: Not all GroupOps can be pipelined. Need to determine when we may
    //       need the group command or a map/reduce.
    private def pipeop = PipelineOp.Group(grouped, by)
    private def coalesce = inlineGroupProjects(this).map((GroupOp.apply _).tupled).getOrElse(this)

    def crush = alwaysCrushPipe(src, pipeop)
    def pipeline = Some(alwaysPipePipe(src, pipeop))
    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)

    // def toProject: WPipelineOp = grouped.value.foldLeft(ProjectOp.uncoalesced(PipelineOp.Reshape.EmptyArr)(src)) {
    //   case (p, (f, v)) => p.set(f, -\/ (v))
    // }

    def empty = copy(grouped = Grouped(ListMap()))

    def getAll: List[(BsonField.Leaf, ExprOp.GroupOp)] =
      grouped.value.toList

    def deleteAll(fields: List[BsonField.Leaf]): WorkflowOp.GroupOp = {
      empty.setAll(getAll.filterNot(t => fields.exists(t._1 == _)))
    }

    def setAll(vs: Seq[(BsonField.Leaf, ExprOp.GroupOp)]) = copy(grouped = Grouped(ListMap(vs: _*)))
  }
  object GroupOp {
    def make(grouped: PipelineOp.Grouped, by: ExprOp \/ PipelineOp.Reshape)(src: WorkflowOp): GroupOp =
      GroupOp(src, grouped, by).coalesce
  }
  val groupOp = GroupOp.make _

  case class SortOp private (src: WorkflowOp, value: NonEmptyList[(BsonField, SortType)])
      extends ShapePreservingOp {
    private def pipeop = PipelineOp.Sort(value)
    def crush = alwaysCrushPipe(src, pipeop)
    def pipeline = Some(alwaysPipePipe(src, pipeop))
    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)
  }
  object SortOp {
    def make(value: NonEmptyList[(BsonField, SortType)])(src: WorkflowOp): SortOp = SortOp(src, value)
  }
  val sortOp = SortOp.make _

  /**
   * TODO: If an OutOp has anything after it, we need to either do
   *   SeqOp(OutOp(src, dst), after(ReadOp(dst), ...))
   * or
   *   ForkOp(src, List(OutOp(_, dst), after(_, ...)))
   * The latter seems preferable, but currently the forking semantics are not
   * clear.
   */
  // case class OutOp private (src: WorkflowOp, collection: Collection) extends ShapePreservingOp {
  //   def coalesce = src.coalesce match {
  //     case read @ ReadOp(_) => read
  //     case _                => this
  //   }
  //   def pipeline = Some(alwaysPipePipe(src, PipelineOp.Out(field)))
  // }

  case class GeoNearOp private (src: WorkflowOp,
                                 near: (Double, Double), distanceField: BsonField,
                                 limit: Option[Int], maxDistance: Option[Double],
                                 query: Option[FindQuery], spherical: Option[Boolean],
                                 distanceMultiplier: Option[Double], includeLocs: Option[BsonField],
                                 uniqueDocs: Option[Boolean])
      extends WPipelineOp {
    private def pipeop = PipelineOp.GeoNear(near, distanceField, limit, maxDistance, query, spherical, distanceMultiplier, includeLocs, uniqueDocs)
    private def coalesce: WorkflowOp = src match {
      case _: GeoNearOp   => this  // TODO: merge the params?
      case p: WPipelineOp => p.reparent(copy(src = p.src))
      case _              => this
    }
    def crush = alwaysCrushPipe(src, pipeop)
    def pipeline = Some(alwaysPipePipe(src, pipeop))
    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)
  }
  object GeoNearOp {
    def make(near: (Double, Double), distanceField: BsonField,
             limit: Option[Int], maxDistance: Option[Double],
             query: Option[FindQuery], spherical: Option[Boolean],
             distanceMultiplier: Option[Double], includeLocs: Option[BsonField],
             uniqueDocs: Option[Boolean])(src: WorkflowOp): WorkflowOp = 
     GeoNearOp(src, near, distanceField, limit, maxDistance, query, spherical, distanceMultiplier, includeLocs, uniqueDocs).coalesce
  }
  val geoNearOp = GeoNearOp.make _

  /**
    Takes a function of two parameters. The first is the current key (which
    defaults to `this._id`, but may have been overridden by previous
    [Flat]MapOps) and the second is the document itself. The function must
    return a 2-element array containing the new key and new value.
    */
  case class MapOp private (src: WorkflowOp, fn: Js.AnonFunDecl) extends SingleSourceOp {
    import MapOp._
    import Js._

    private def coalesce: SingleSourceOp = src match {
      case MapOp(src0, fn0)     => chain(src0, mapOp(compose(fn, fn0)))
      case FlatMapOp(src0, fn0) =>
        chain(src0, flatMapOp(FlatMapOp.mapCompose(fn, fn0)))
      case csrc                 => MapOp(csrc, fn)
    }

    private def newMR(base: DocVar, src: WorkflowTask, sel: Option[Selector], sort: Option[NonEmptyList[(BsonField, SortType)]], count: Option[Long]) = 
      (ExprVar,
        MapReduceTask(src,
          MapReduce(
            mapFn(base match {
              case DocVar(DocVar.ROOT, None) => this.fn
              case _ => compose(this.fn, mapProject(base))
            }),
            ReduceOp.reduceNOP,
            selection = sel, inputSort = sort, limit = count)))

    def crush = {
      val (base, crushed) = src.crush
      crushed match {
        case MapReduceTask(src0, mr @ MapReduce(_, _, _, _, _, _, None, _, _, _)) =>
          (base, MapReduceTask(src0, mr applyLens MapReduce._finalizer set Some(finalizerFn(this.fn))))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel)))) =>
          newMR(base, src0, Some(sel), None, None)
        case PipelineTask(src0, Pipeline(List(PipelineOp.Sort(sort)))) =>
          newMR(base, src0, None, Some(sort), None)
        case PipelineTask(src0, Pipeline(List(PipelineOp.Limit(count)))) =>
          newMR(base, src0, None, None, Some(count))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel), PipelineOp.Sort(sort)))) =>
          newMR(base, src0, Some(sel), Some(sort), None)
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel), PipelineOp.Limit(count)))) =>
          newMR(base, src0, Some(sel), None, Some(count))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Sort(sort), PipelineOp.Limit(count)))) =>
          newMR(base, src0, None, Some(sort), Some(count))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel), PipelineOp.Sort(sort), PipelineOp.Limit(count)))) =>
          newMR(base, src0, Some(sel), Some(sort), Some(count))
        case srcTask =>
          newMR(base, srcTask, None, None, None)
      }
    }

    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)
  }
  object MapOp {
    import Js._

    def make(fn: Js.AnonFunDecl)(src: WorkflowOp): SingleSourceOp = MapOp(src, fn).coalesce

    def compose(g: Js.AnonFunDecl, f: Js.AnonFunDecl): Js.AnonFunDecl =
      AnonFunDecl(List("key", "value"), List(
        Return(Call(Select(g, "apply"),
          List(Null, Call(f, List(Ident("key"), Ident("value"))))))))

    def mapProject(base: DocVar) =
      AnonFunDecl(List("key", "value"), List(
        Return(AnonElem(List(Ident("key"), base.toJs(Ident("value")))))))


    def mapKeyVal(idents: (String, String), key: Js.Expr, value: Js.Expr) =
      AnonFunDecl(List(idents._1, idents._2),
        List(Return(AnonElem(List(key, value)))))
    def mapMap(ident: String, transform: Js.Expr) =
      mapKeyVal(("key", ident), Ident("key"), transform)
    val mapNOP = mapMap("value", Ident("value"))

    def finalizerFn(fn: Js.Expr) =
      AnonFunDecl(List("key", "value"),
        List(Return(Access(
          Call(fn, List(Ident("key"), Ident("value"))),
          Num(1, false)))))

    def mapFn(fn: Js.Expr) =
      AnonFunDecl(Nil,
        List(Call(Select(Ident("emit"), "apply"),
          List(
            Null,
            Call(fn, List(Select(This, "_id"), This))))))
  }
  val mapOp = MapOp.make _

  /**
    Takes a function of two parameters. The first is the current key (which
    defaults to `this._id`, but may have been overridden by previous
    [Flat]MapOps) and the second is the document itself. The function must
    return an array of 2-element arrays, each containing a new key and a new
    value.
    */
  case class FlatMapOp private (src: WorkflowOp, fn: Js.AnonFunDecl) extends SingleSourceOp {
    import FlatMapOp._
    import Js._

    private def coalesce: FlatMapOp = src match {
      case MapOp(src0, fn0)     => FlatMapOp(src0, MapOp.compose(fn, fn0))
      case FlatMapOp(src0, fn0) =>
        FlatMapOp(src0, kleisliCompose(fn, fn0))
      case csrc                 => FlatMapOp(csrc, fn)
    }

    private def newMR(base: DocVar, src: WorkflowTask, sel: Option[Selector], sort: Option[NonEmptyList[(BsonField, SortType)]], count: Option[Long]) =
      (ExprVar,
        MapReduceTask(src,
          MapReduce(
            mapFn(base match {
              case DocVar(DocVar.ROOT, None) => this.fn
              case _ => MapOp.compose(this.fn, MapOp.mapProject(base))
            }),
            ReduceOp.reduceNOP,
            selection = sel, inputSort = sort, limit = count)))
    def crush = {
      val (base, crushed) = src.crush
      crushed match {
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel)))) =>
          newMR(base, src0, Some(sel), None, None)
        case PipelineTask(src0, Pipeline(List(PipelineOp.Sort(sort)))) =>
          newMR(base, src0, None, Some(sort), None)
        case PipelineTask(src0, Pipeline(List(PipelineOp.Limit(count)))) =>
          newMR(base, src0, None, None, Some(count))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel), PipelineOp.Sort(sort)))) =>
          newMR(base, src0, Some(sel), Some(sort), None)
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel), PipelineOp.Limit(count)))) =>
          newMR(base, src0, Some(sel), None, Some(count))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Sort(sort), PipelineOp.Limit(count)))) =>
          newMR(base, src0, None, Some(sort), Some(count))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel), PipelineOp.Sort(sort), PipelineOp.Limit(count)))) =>
          newMR(base, src0, Some(sel), Some(sort), Some(count))
        case srcTask =>
          newMR(base, srcTask, None, None, None)
      }
    }

    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)
  }
  object FlatMapOp {
    import Js._

    def make(fn: Js.AnonFunDecl)(src: WorkflowOp): FlatMapOp = FlatMapOp(src, fn).coalesce

    private def composition(g: Js.AnonFunDecl, f: Js.AnonFunDecl) =
      Call(
        Select(Call(f, List(Ident("key"), Ident("value"))), "map"),
        List(AnonFunDecl(List("args"), List(
          Return(Call(Select(g, "apply"), List(Null, Ident("args"))))))))

    def kleisliCompose(g: Js.AnonFunDecl, f: Js.AnonFunDecl) =
      AnonFunDecl(List("key", "value"), List(
        Return(
          Call(
            Select(Select(AnonElem(Nil), "concat"), "apply"),
            List(AnonElem(Nil), composition(g, f))))))

    def mapCompose(g: Js.AnonFunDecl, f: Js.AnonFunDecl) =
      AnonFunDecl(List("key", "value"), List(Return(composition(g, f))))

    def mapFn(fn: Js.Expr) =
      AnonFunDecl(Nil,
        List(
          Call(
            Select(
              Call(fn, List(Select(This, "_id"), This)),
              "map"),
            List(AnonFunDecl(List("__rez"),
              List(Call(Select(Ident("emit"), "apply"),
                List(Null, Ident("__rez")))))))))
  }
  val flatMapOp = FlatMapOp.make _

  /**
    Takes a function of two parameters – a key and an array of values. The
    function must return a single value.
    */
  case class ReduceOp private (src: WorkflowOp, fn: Js.AnonFunDecl) extends SingleSourceOp {
    import ReduceOp._

    private def newMR(base: DocVar, src: WorkflowTask, sel: Option[Selector], sort: Option[NonEmptyList[(BsonField, SortType)]], count: Option[Long]) =
      (ExprVar,
        MapReduceTask(src,
          MapReduce(
            MapOp.mapFn(base match {
              case DocVar(DocVar.ROOT, None) => MapOp.mapNOP
              case _                         => MapOp.mapProject(base)
            }),
            this.fn,
            selection = sel, inputSort = sort, limit = count)))

    def crush = {
      val (base, crushed) = src.crush
      crushed match {
        case MapReduceTask(src0, mr @ MapReduce(_, reduceNOP, _, _, _, _, None, _, _, _)) =>
          (base, MapReduceTask(src0, mr applyLens MapReduce._reduce set this.fn))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel)))) =>
          newMR(base, src0, Some(sel), None, None)
        case PipelineTask(src0, Pipeline(List(PipelineOp.Sort(sort)))) =>
          newMR(base, src0, None, Some(sort), None)
        case PipelineTask(src0, Pipeline(List(PipelineOp.Limit(count)))) =>
          newMR(base, src0, None, None, Some(count))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel), PipelineOp.Sort(sort)))) =>
          newMR(base, src0, Some(sel), Some(sort), None)
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel), PipelineOp.Limit(count)))) =>
          newMR(base, src0, Some(sel), None, Some(count))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Sort(sort), PipelineOp.Limit(count)))) =>
          newMR(base, src0, None, Some(sort), Some(count))
        case PipelineTask(src0, Pipeline(List(PipelineOp.Match(sel), PipelineOp.Sort(sort), PipelineOp.Limit(count)))) =>
          newMR(base, src0, Some(sel), Some(sort), Some(count))
        case srcTask =>
          newMR(base, srcTask, None, None, None)
      }
    }

    def reparent(newSrc: WorkflowOp) = copy(src = newSrc)
  }
  object ReduceOp {
    import Js._

    def make(fn: Js.AnonFunDecl)(src: WorkflowOp): ReduceOp = ReduceOp(src, fn)
    
    val reduceNOP =
      AnonFunDecl(List("key", "values"), List(
        Return(Access(Ident("values"), Num(0, false)))))

    def copyOneField(key: Js.Expr => Js.Expr, expr: Js.Expr):
        Js.Expr => Js.Stmt =
      base => Js.BinOp("=", key(base), expr)

    def copyAllFields(expr: Js.Expr): Js.Expr => Js.Stmt = base =>
      Js.ForIn(Js.Ident("attr"), expr,
        Js.If(
          Js.Call(Js.Select(expr, "hasOwnProperty"), List(Js.Ident("attr"))),
          copyOneField(
            Js.Access(_, Js.Ident("attr")),
            Js.Access(expr, Js.Ident("attr")))(base),
          None))

    val reduceFoldLeft =
      AnonFunDecl(List("key", "values"), List(
        VarDef(List("rez" -> AnonObjDecl(Nil))),
        Call(Select(Ident("values"), "forEach"),
          List(AnonFunDecl(List("value"),
            List(copyAllFields(Ident("value"))(Ident("rez")))))),
        Return(Ident("rez"))))
  }
  val reduceOp = ReduceOp.make _

  /**
    Performs a sequence of operations, sequentially, merging their results.
    */
  case class FoldLeftOp private (head: WorkflowOp, tail: NonEmptyList[WorkflowOp]) extends WorkflowOp {
    def srcs = head :: tail.toList
    private def coalesce = head match {
      case FoldLeftOp(head0, tail0) => FoldLeftOp.make(head0, tail0 append tail)
      case _                        => this
    }
    def crush =
      (ExprVar,
        FoldLeftTask(
          head.crush._2,
          tail.map(_.crush._2 match {
            case MapReduceTask(src, mr) =>
              // FIXME: FoldLeftOp currently always reduces, but in future we’ll
              //        want to have more control.
              MapReduceTask(src,
                mr applyLens MapReduce._out set Some(MapReduce.WithAction(MapReduce.Action.Reduce)))
            // NB: `finalize` should ensure that the final op is always a
            //     ReduceOp.
            case src => sys.error("not a mapReduce: " + src)
          })))
  }
  object FoldLeftOp {
    def make(head: WorkflowOp, tail: NonEmptyList[WorkflowOp]): FoldLeftOp = FoldLeftOp(head, tail).coalesce
  }
  def foldLeftOp(first: WorkflowOp, second: WorkflowOp, rest: WorkflowOp*) = FoldLeftOp.make(first, NonEmptyList.nel(second, rest.toList))

  case class JoinOp private (ssrcs: Set[WorkflowOp]) extends WorkflowOp {
    def srcs = ssrcs.toList
    def crush = (ExprVar, JoinTask(ssrcs.map(_.crush._2)))
  }
  object JoinOp {
    def make(ssrcs: Set[WorkflowOp]): JoinOp = JoinOp(ssrcs)
  }
  val joinOp = JoinOp.make _
  
  implicit def WorkflowOpRenderTree(implicit RS: RenderTree[Selector], RE: RenderTree[ExprOp], RG: RenderTree[PipelineOp.Grouped], RJ: RenderTree[Js]): RenderTree[WorkflowOp] = new RenderTree[WorkflowOp] {
    def nodeType(subType: String) = "WorkflowOp" :: subType :: Nil

    def chain(op: SingleSourceOp): List[WorkflowOp] = {
      def loop(op: SingleSourceOp, acc: List[WorkflowOp]): List[WorkflowOp] = {
        val foo = op :: acc
        op.src match {
          case src: SingleSourceOp => loop(src, foo)
          case src                 => src :: foo
        }
      }
      loop(op, Nil)
    }

    def renderFlat(op: WorkflowOp) = op match {
      case PureOp(value)         => Terminal(value.toString, nodeType("PureOp"))
      case ReadOp(coll)          => Terminal(coll.name, nodeType("ReadOp"))
      case MatchOp(src, sel)     => NonTerminal("",
                                    RS.render(sel) ::
                                      Nil,
                                    nodeType("MatchOp"))
      case ProjectOp(src, shape) => NonTerminal("",
                                      PipelineOp.renderReshape(shape),
                                      nodeType("ProjectOp"))
      case RedactOp(src, value) => NonTerminal("", 
                                      RE.render(value) ::
                                        Nil,
                                    nodeType("RedactOp"))
      case LimitOp(src, count)  => Terminal(count.toString, nodeType("LimitOp"))
      case SkipOp(src, count)   => Terminal(count.toString, nodeType("SkipOp"))
      case UnwindOp(src, field) => Terminal(field.toString, nodeType("UnwindOp"))
      case GroupOp(src, grouped, -\/ (expr))
                                => NonTerminal("",
                                    RG.render(grouped) ::
                                      Terminal(expr.toString, nodeType("By")) ::
                                      Nil,
                                    nodeType("GroupOp"))
      case GroupOp(src, grouped, \/- (by))
                                => NonTerminal("",
                                    RG.render(grouped) ::
                                      NonTerminal("", PipelineOp.renderReshape(by), nodeType("By")) ::
                                      Nil,
                                    nodeType("GroupOp"))
      case SortOp(src, value)   => NonTerminal("",
                                    value.map { case (field, st) => Terminal(field.asText + " -> " + st, nodeType("SortKey")) }.toList,
                                    nodeType("SortOp"))
      case GeoNearOp(src, near, distanceField, limit, maxDistance, query, spherical, distanceMultiplier, includeLocs, uniqueDocs)
                                => NonTerminal("",
                                      Terminal(near.toString, nodeType("GeoNearOp") :+ "Near") ::
                                        Terminal(distanceField.toString, nodeType("GeoNearOp") :+ "DistanceField") ::
                                        Terminal(limit.toString, nodeType("GeoNearOp") :+ "Limit") ::
                                        Terminal(maxDistance.toString, nodeType("GeoNearOp") :+ "MaxDistance") ::
                                        Terminal(query.toString, nodeType("GeoNearOp") :+ "Query") ::
                                        Terminal(spherical.toString, nodeType("GeoNearOp") :+ "Spherical") ::
                                        Terminal(distanceMultiplier.toString, nodeType("GeoNearOp") :+ "DistanceMultiplier") ::
                                        Terminal(includeLocs.toString, nodeType("GeoNearOp") :+ "IncludeLocs") ::
                                        Terminal(uniqueDocs.toString, nodeType("GeoNearOp") :+ "UniqueDocs") ::
                                        Nil,
                                    nodeType("GeoNearOp"))

      case MapOp(src, fn)       => NonTerminal("", RJ.render(fn) :: Nil, nodeType("MapOp"))
      case FlatMapOp(src, fn)   => NonTerminal("", RJ.render(fn) :: Nil, nodeType("FlatMapOp"))
      case ReduceOp(src, fn)    => NonTerminal("", RJ.render(fn) :: Nil, nodeType("ReduceOp"))

      case op                   => render(op)
    }

    def render(v: WorkflowOp) = v match {
      case op: SourceOp         => renderFlat(op)

      case op: SingleSourceOp   => NonTerminal("", chain(op).map(renderFlat(_)), nodeType("Chain"))

      case op @ FoldLeftOp(_, _) => NonTerminal("", op.srcs.map(WorkflowOpRenderTree.render(_)), nodeType("FoldLeftOp"))
      case op @ JoinOp(ssrcs)    => NonTerminal("", op.srcs.map(WorkflowOpRenderTree.render(_)), nodeType("JoinOp"))
    }
  }
}
