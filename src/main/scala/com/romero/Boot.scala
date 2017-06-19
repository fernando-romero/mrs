package com.romero

import akka.actor.{Actor, ActorSystem, Props, Stash}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.mongodb.scala.MongoClient
import org.mongodb.scala.bson._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

// Http payloads

case class RegisterScreening(imdbId: String, screenId: String, availableSeats: Int) {
  require(imdbId.nonEmpty, "imdbId can't be empty")
  require(screenId.nonEmpty, "imdbId can't be empty")
  require(availableSeats > 0, "availableSeats should be more than 0")
}

case class ReserveSeat(imdbId: String, screenId: String) {
  require(imdbId.nonEmpty, "imdbId can't be empty")
  require(screenId.nonEmpty, "imdbId can't be empty")
}

case class Screening(imdbId: String, screenId: String, movieTitle: String, availableSeats: Int, reservedSeats: Int) {
  require(imdbId.nonEmpty, "imdbId can't be empty")
  require(screenId.nonEmpty, "imdbId can't be empty")
  require(availableSeats >= 0, "availableSeats can't be negative")
  require(reservedSeats >= 0, "reservedSeats can't be negative")
}

// Akka messages

case object ScreeningAlreadyRegistered
case object NoSeatsAvailable
case object ScreeningNotFound

// Json

trait JsonSupport extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val registerScreeningFmt: RootJsonFormat[RegisterScreening] = jsonFormat3(RegisterScreening)
  implicit val reserveSeatFmt: RootJsonFormat[ReserveSeat] = jsonFormat2(ReserveSeat)
  implicit val screeningFmt: RootJsonFormat[Screening] = jsonFormat5(Screening)
}

// Persistence

trait Storage {
  def getScreenings: Future[Seq[Screening]]
  def getScreening(imdbId: String, screenId: String): Future[Option[Screening]]
  def registerScreening(screening: Screening): Future[Unit]
  def updateScreening(screening: Screening): Future[Unit]
}

class MongoStorage(uri: String) extends Storage {
  private val client = MongoClient(uri)
  private val db = client.getDatabase("mrs")
  private val col = db.getCollection("screenings")

  private def toModel(doc: Document): Option[Screening] = {
    Try {
      Screening(
        imdbId = doc.getString("imdbId"),
        screenId = doc.getString("screenId"),
        movieTitle = doc.getString("movieTitle"),
        availableSeats = doc.getInteger("availableSeats"),
        reservedSeats = doc.getInteger("reservedSeats")
      )
    }.toOption
  }

  private def toDoc(screening: Screening) = {
    Document("imdbId" -> screening.imdbId) +
      ("screenId" -> screening.screenId) +
      ("movieTitle" -> screening.movieTitle) +
      ("availableSeats" -> screening.availableSeats) +
      ("reservedSeats" -> screening.reservedSeats)
  }

  override def getScreenings: Future[Seq[Screening]] = {
    col.find().toFuture().map(_.flatMap(toModel))
  }

  override def getScreening(imdbId: String, screenId: String): Future[Option[Screening]] = {
    val query = and(equal("imdbId", imdbId), equal("screenId", screenId))
    col.find(query).first().head().map(toModel)
  }

  override def registerScreening(screening: Screening): Future[Unit] = {
    col.insertOne(toDoc(screening)).head().map(_ => ())
  }

  override def updateScreening(screening: Screening): Future[Unit] = {
    val query = and(equal("imdbId", screening.imdbId), equal("screenId", screening.screenId))
    val update = combine(
      set("availableSeats", screening.availableSeats),
      set("reservedSeats", screening.reservedSeats))
    col.updateOne(query, update).head().map(_ => ())
  }
}

// Actors

object Reserver {
  def props(storage: Storage): Props = Props(new Reserver(storage))
}

class Reserver(storage: Storage) extends Actor with Stash {

  def receive: Receive = {
    case rs: RegisterScreening =>
      pipe(storage.getScreening(rs.imdbId, rs.screenId)).to(self, sender)
      context.become(registering(rs))

    case rs: ReserveSeat =>
      pipe(storage.getScreening(rs.imdbId, rs.screenId)).to(self, sender)
      context.become(reserving(rs))
  }

  def registering(rs: RegisterScreening): Receive = {
    case Some(_) =>
      sender ! ScreeningAlreadyRegistered
      context.unbecome()
      unstashAll()

    case None =>
      val screening = Screening(rs.imdbId, rs.screenId, "NA", rs.availableSeats, 0)
      pipe(storage.registerScreening(screening).map(_ => screening)).to(self, sender)

    case s: Screening =>
      sender ! s
      context.unbecome()
      unstashAll()

    case f: akka.actor.Status.Failure =>
      sender ! f
      context.unbecome()
      unstashAll()

    case _ => stash()
  }

  def reserving(rs: ReserveSeat): Receive = {
    case Some(screening: Screening) =>
      if (screening.availableSeats > 0) {
        val updated = screening.copy(
          availableSeats = screening.availableSeats - 1,
          reservedSeats = screening.reservedSeats + 1
        )
        pipe(storage.updateScreening(updated).map(_ => updated)).to(self, sender)
      } else {
        sender ! NoSeatsAvailable
        context.unbecome()
        unstashAll()
      }

    case None =>
      sender ! ScreeningNotFound
      context.unbecome()
      unstashAll()

    case s: Screening =>
      sender ! s
      context.unbecome()
      unstashAll()

    case f: akka.actor.Status.Failure =>
      sender ! f
      context.unbecome()
      unstashAll()

    case _ => stash()
  }
}

object Manager {
  def props(storage: Storage): Props = Props(new Manager(storage))
}

class Manager(storage: Storage) extends Actor {

  private def getReserver(imdbId: String, screenId: String) = {
    val name = s"$imdbId-$screenId"
    context.child(name).getOrElse {
      context.actorOf(Reserver.props(storage), name)
    }
  }

  def receive: Receive = {
    case rs: RegisterScreening =>
      getReserver(rs.imdbId, rs.screenId).forward(rs)
    case rs: ReserveSeat =>
      getReserver(rs.imdbId, rs.screenId).forward(rs)
  }
}

// Server

object Boot extends App with JsonSupport {
  implicit val system = ActorSystem("mrs")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  implicit val timeout = Timeout(5.seconds)

  val conf = ConfigFactory.load

  val mongoUri = conf.getString("mongoUri")
  val storage = new MongoStorage(mongoUri)
  val manager = system.actorOf(Manager.props(storage))

  val route =
    path("health") {
      get {
        complete(StatusCodes.OK)
      }
    } ~
    path("screenings") {
      post {
        entity(as[RegisterScreening]) { rs =>
          onComplete(ask(manager, rs)) {
            case Success(s: Screening) =>
              respondWithHeader(RawHeader("Location", s"/movies/${s.imdbId}/screenings/${s.screenId}")) {
                complete(StatusCodes.Created, s)
              }
            case Success(ScreeningAlreadyRegistered) =>
              complete(StatusCodes.Forbidden, "screening already registered")
            case Success(akka.actor.Status.Failure(t)) =>
              complete(StatusCodes.InternalServerError, t.getMessage)
            case Success(_) =>
              complete(StatusCodes.InternalServerError, "something unexpected happened")
            case Failure(t) =>
              complete(StatusCodes.InternalServerError, t.getMessage)
          }
        }
      } ~
      put {
        entity(as[ReserveSeat]) { rs =>
          onComplete(ask(manager, rs)) {
            case Success(s: Screening) =>
              complete(s)
            case Success(NoSeatsAvailable) =>
              complete(StatusCodes.Forbidden, "no seats available")
            case Success(ScreeningNotFound) =>
              complete(StatusCodes.NotFound, "screening not found")
            case Success(akka.actor.Status.Failure(t)) =>
              complete(StatusCodes.InternalServerError, t.getMessage)
            case Success(_) =>
              complete(StatusCodes.InternalServerError, "something unexpected happened")
            case Failure(t) =>
              complete(StatusCodes.InternalServerError, t.getMessage)
          }
        }
      } ~
      get {
        onComplete(storage.getScreenings) {
          case Success(screenings: Seq[Screening]) =>
            complete(screenings)
          case Failure(t) =>
            complete(StatusCodes.InternalServerError, t.getMessage)
        }
      }
    } ~
    path("movies" / Segment / "screenings" / Segment)  { (imdbId, screenId) =>
      onComplete(storage.getScreening(imdbId, screenId)) {
        case Success(Some(s)) =>
          complete(s)
        case Success(None) =>
          complete(StatusCodes.NotFound)
        case Failure(t) =>
          complete(StatusCodes.InternalServerError, t.getMessage)
      }
    }

  val interface = conf.getString("interface")
  val port = conf.getInt("port")
  Http().bindAndHandle(route, interface, port)
}