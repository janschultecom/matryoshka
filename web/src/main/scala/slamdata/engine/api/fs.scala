package slamdata.engine.api

import scala.collection.immutable.{TreeSet}

import slamdata.engine._
import slamdata.engine.sql.Query
import slamdata.engine.config._
import slamdata.engine.fs._
import slamdata.engine.fp._

import argonaut._
import Argonaut._

import org.http4s.{Query => HQuery, _}
import org.http4s.dsl.{Path => HPath, _}
import org.http4s.argonaut._
import org.http4s.headers._
import org.http4s.server._
import org.http4s.util.{CaseInsensitiveString, Renderable}

import scalaz._
import Scalaz._

import scalaz.concurrent._
import scalaz.stream._

class FileSystemApi(fs: FSTable[Backend]) {
  import java.io.{FileSystem => _, _}
  import Method.{MOVE, OPTIONS}

  private def responseCodec(accept: Option[Accept]): (DataCodec, MediaType) = {
    val jsonTypes = NonEmptyList(
      new MediaType("application", "ldjson"),
      new MediaType("application", "x-ldjson"),
      new MediaType("application", "json",
        extensions = Map("boundary" -> "NL")))

    def responseType(mode: String) =
      jsonTypes.head.withExtensions(jsonTypes.head.extensions ++ Map("mode" -> mode))

    (for {
      acc       <- accept
      // TODO: MediaRange needs an Order instance – combining QValue ordering
      //       with specificity (EG, application/json sorts before
      //       application/* if they have the same q-value).
      mediaType <- acc.values.toList.sortBy(_.qValue).find(a => jsonTypes.toList.exists(a.satisfies(_)))
      mode      <- mediaType.extensions.get("mode")
      codec     <- mode match {
        case "precise" => Some((DataCodec.Precise, responseType("precise")))
        case _         => None
      }
    } yield codec).getOrElse((DataCodec.Readable, responseType("readable")))
  }

  private def jsonStream(codec: DataCodec, v: Process[Task, Data])(implicit EE: EncodeJson[DataEncodingError]): Task[Response] = {
    Ok(v.map { data =>
      DataCodec.render(data)(codec).fold(err => EE.encode(err).toString, identity) + "\r\n"
    })
  }

  private def responseStream(accept: Option[Accept], v: Process[Task, Data]):
      Task[Response] = {
    val (codec, mediaType) = responseCodec(accept)
    jsonStream(codec, v).map(_.putHeaders(`Content-Type`(mediaType, Some(Charset.`UTF-8`))))
  }

  private def lookupBackend(path: Path): Task[Response] \/ (Backend, Path, Path) =
    fs.lookup(path) \/> (NotFound("No data source is mounted to the path " + path))

  private def backendFor(path: Path): Task[Response] \/ (Backend, Path) =
    lookupBackend(path).map { case (backend, mountPath, _) => (backend, mountPath) }

  private def dataSourceFor(path: Path): Task[Response] \/ (FileSystem, Path) =
    lookupBackend(path).map { case (backend, _, relPath) => (backend.dataSource, relPath) }

  private def errorResponse(status: org.http4s.dsl.impl.EntityResponseGenerator, e: Throwable) = {
    e match {
      case PhaseError(phases, causedBy) => status(Json.obj(
          "error"  := causedBy.getMessage,
          "phases" := phases))

      case _ => status(e.getMessage)
    }
  }

  private def vars(req: Request) = Variables(req.params.map { case (k, v) => (VarName(k), VarValue(v)) })

  private val QueryParameterMustContainQuery = BadRequest("The request must contain a query")
  private val POSTContentMustContainQuery    = BadRequest("The body of the POST must contain a query")
  private val DestinationHeaderMustExist     = BadRequest("The 'Destination' header must be specified")

  private def upload[A](body: String, path: Path, f: (FileSystem, Path, Process[Task, Data]) => List[Throwable] \/ Unit) = {
    val codec = DataCodec.Precise
    def parseJsonLines(str: String): (List[WriteError], List[Data]) =
      unzipDisj(str.split("\n").map(line => DataCodec.parse(line)(codec).leftMap(
        e => WriteError(Data.Str("parse error: " + line), Some(e.message)))).toList)

    def errorBody(status: org.http4s.dsl.impl.EntityResponseGenerator, errs: List[Throwable])(implicit EJ: EncodeJson[WriteError]) =
      status(Json(
        "errors" := errs.map {
          case e @ WriteError(_, _) => EJ.encode(e)
          case e: slamdata.engine.Error => Json("detail" := e.message)
          case e => Json("detail" := e.toString)
        }))

    (for {
      t1   <- dataSourceFor(path)
      (dataSource, relPath) = t1

      (errs, json) = parseJsonLines(body)
      _    <- if (!errs.isEmpty) -\/ (errorBody(BadRequest, errs)) else \/- (())

      _    <- f(dataSource, relPath, Process.emitAll(json)).leftMap {
        case (pe @ PathError(_)) :: Nil => errorBody(BadRequest, pe :: Nil)
        case es                         => errorBody(InternalServerError, es)
      }
    } yield Ok("")).fold(identity, identity)
  }

  object AsPath {
    def unapply(p: HPath): Option[Path] = {
      Some(Path("/" + p.toList.map(java.net.URLDecoder.decode(_, "UTF-8")).mkString("/")))
    }
  }

  object AsDirPath {
    def unapply(p: HPath): Option[Path] = AsPath.unapply(p).map(_.asDir)
  }

  implicit val QueryDecoder = new QueryParamDecoder[Query] {
    def decode(value: QueryParameterValue): ValidationNel[ParseFailure, Query] =
      Query(value.value).successNel[ParseFailure]
  }
  object Q extends QueryParamDecoderMatcher[Query]("q")

  object Offset extends OptionalQueryParamDecoderMatcher[Long]("offset")
  object Limit extends OptionalQueryParamDecoderMatcher[Long]("limit")

  // Note: CORS middleware is coming in http4s post-0.6.5
  val corsHeaders = List(
    AccessControlAllowOriginAll,
    `Access-Control-Allow-Methods`(List("GET", "PUT", "POST", "DELETE", "MOVE", "OPTIONS")),
    `Access-Control-Max-Age`(20*24*60*60),
    `Access-Control-Allow-Headers`(List(Destination)))  // NB: actually needed for POST only

  // TODO: Using the Option Kleisli category or scalaz.NullResult instead of PartialFunction.
  def corsService(pf: PartialFunction[Request, Task[Response]]) =
    HttpService(pf.orElse[Request, Task[Response]] {
      case OPTIONS -> _ => Ok()
    }).map(_.putHeaders(corsHeaders: _*))

  def queryService = {
    corsService {
      case req @ GET -> AsDirPath(path) :? Q(query) =>
        (for {
          b     <- backendFor(path)
          (backend, mountPath) = b

          (phases, resultT) = backend.eval(QueryRequest(query, None, mountPath, path))
          result <- resultT.attemptRun.leftMap(e =>  errorResponse(BadRequest, e))
        } yield responseStream(req.headers.get(Accept), result)).fold(identity, identity)

      case GET -> _ => QueryParameterMustContainQuery

      case req @ POST -> AsDirPath(path) =>
        def go(query: Query): Task[Response] =
          (for {
            outRaw <- req.headers.get(Destination).map(_.value) \/> DestinationHeaderMustExist

            b     <- backendFor(path)
            (backend, mountPath) = b

            out     <- Path(outRaw).interpret(mountPath, path).leftMap(e => errorResponse(BadRequest, e))
            (phases, resultT) = backend.run(QueryRequest(query, Some(out), mountPath, path, vars(req)))
            out     <- resultT.attemptRun.leftMap(e => errorResponse(InternalServerError, e))
          } yield {
            Ok(Json.obj(
              "out"    := (mountPath ++ out.path).pathname,
              "phases" := phases))
          }).fold(identity, identity)

        for {
          query <- EntityDecoder.decodeString(req)
          resp <- if (query != "") go(Query(query)) else POSTContentMustContainQuery
        } yield resp
    }
  }

  def compileService = {
    def go(path: Path, query: Query) =
      (for {
        b     <- backendFor(path)
        (backend, mountPath) = b

        (phases, resultT) = backend.eval(QueryRequest(query, None, mountPath, path))

        plan  <- phases.lastOption \/> InternalServerError("no plan")
      } yield plan match {
          case PhaseResult.Error(name, value)  => errorResponse(BadRequest, value)
          case PhaseResult.Tree(name, value)   => Ok(Json(name := value))
          case PhaseResult.Detail(name, value) => Ok(name + "\n" + value)
      }).fold(identity, identity)

    corsService {
      case GET -> AsDirPath(path) :? Q(query) => go(path, query)

      case GET -> _ => QueryParameterMustContainQuery

      case req @ POST -> AsDirPath(path) => for {
        query <- EntityDecoder.decodeString(req)
        resp  <- if (query != "") go(path, Query(query)) else POSTContentMustContainQuery
      } yield resp
    }
  }

  def metadataService = corsService {
    case GET -> AsDirPath(path) =>
      if (path == Path("/") && fs.isEmpty)
        Ok(Json.obj("children" := List[Path]()))
      else
        dataSourceFor(path) match {
          case \/- ((ds, relPath)) =>
            ds.ls(relPath).attemptRun.fold(
              e => e match {
                case f: FileSystem.FileNotFoundError => NotFound()
                case _ => throw e
              },
              paths =>
                Ok(Json.obj("children" := paths)))

          case _ => {
            val fsChildren = fs.children(path)

            if (fsChildren.isEmpty) NotFound()
            else
              Ok(Json.obj("children" := fsChildren.map(p =>
                    Json("name" := p.simplePathname, "type" := "mount"))))
          }
        }
      // TODO: Use typesafe data structure and just serialize that.
  }

  def dataService = corsService {
    case req @ GET -> AsPath(path) :? Offset(offset) +& Limit(limit) =>
      (for {
        t <- dataSourceFor(path)
        (dataSource, relPath) = t
      } yield responseStream(req.headers.get(Accept), dataSource.scan(relPath, offset, limit))).getOrElse(NotFound())

    case req @ PUT -> AsPath(path) => for {
      body <- EntityDecoder.decodeString(req)
      resp <- upload(body, path, (ds, p, json) => ds.save(p, json).attemptRun.leftMap(_ :: Nil))
    } yield resp

    case req @ POST -> AsPath(path) => for {
      body <- EntityDecoder.decodeString(req)
      resp <- upload(body, path, (ds, p, json) => {
        val errors = ds.append(p, json).runLog
        errors.attemptRun.fold(err => -\/ (err :: Nil), errs => if (!errs.isEmpty) -\/ (errs.toList) else \/- (()))
      })
    } yield resp

    case req @ MOVE -> AsPath(path) =>
      (for {
          dstRaw <- req.headers.get(Destination).map(_.value) \/> DestinationHeaderMustExist
          dst <- Path(dstRaw).from(path.dirOf).leftMap(e => BadRequest("Invalid destination path: " + e.getMessage))

          t1 <- dataSourceFor(path)
          (srcDataSource, srcPath) = t1

          t2 <- dataSourceFor(dst)
          (dstDataSource, dstPath) = t2

          _ <- if (srcDataSource != dstDataSource) -\/(InternalServerError("Cannot copy/move across backends"))
               else srcDataSource.move(srcPath, dstPath).attemptRun.leftMap(e => errorResponse(InternalServerError, e))
        } yield Created("")
      ).fold(identity, identity)

    case DELETE -> AsPath(path) =>
      (for {
          t <- dataSourceFor(path)
          (dataSource, relPath) = t
          _ <- dataSource.delete(relPath).attemptRun.leftMap(e => errorResponse(InternalServerError, e))
        } yield Ok("")
      ).fold(identity, identity)
  }
}
