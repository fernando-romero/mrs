package com.romero

import akka.actor.{Actor, ActorSystem, Props, Stash}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.mongodb.scala.MongoClient
import org.mongodb.scala.model.Updates._
import org.mongodb.scala.model.Filters._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.duration._
import scala.concurrent.Future
import org.mongodb.scala.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

case class RegisterScreening(imdbId: String, screenId: String, availableSeats: Int) {
  require(availableSeats > 0, "availableSeats should be more than 0")
}

case class Screening(imdbId: String, screenId: String, movieTitle: String, availableSeats: Int, reservedSeats: Int) {
  require(availableSeats > 0, "availableSeats should be more than 0")
  require(reservedSeats <= availableSeats, "reservedSeats can't be more than availableSeats")
}

trait JsonSupport extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val registerScreeningFmt: RootJsonFormat[RegisterScreening] = jsonFormat3(RegisterScreening)
  implicit val screeningFmt: RootJsonFormat[Screening] = jsonFormat5(Screening)
}

trait Storage {
  def getScreenings: Future[Seq[Screening]]
  def getScreening(imdbId: String, screenId: String): Future[Option[Screening]]
  def registerScreening(screening: Screening): Future[Screening]
  def reserveSeat(screening: Screening): Future[Option[Screening]]
}

class MongoStorage extends Storage {
  private val client = MongoClient()
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

  override def registerScreening(screening: Screening): Future[Screening] = {
    col.insertOne(toDoc(screening)).head().map(_ => screening)
  }

  override def reserveSeat(screening: Screening): Future[Option[Screening]] = {
    val query = and(equal("imdbId", screening.imdbId), equal("screenId", screening.screenId))
    val update = combine(
      set("availableSeats", screening.availableSeats - 1),
      set("reservedSeats", screening.reservedSeats + 1))
    col.findOneAndUpdate(query, update).head().map(toModel)
  }
}

object Reserver {
  def props(imdbId: String, screenId: String, storage: Storage): Props = Props(new Reserver(imdbId, screenId, storage))
}

class Reserver(imdbId: String, screenId: String, storage: Storage) extends Actor with Stash {
  val log = Logging(context.system, this)

  import context.dispatcher

  def receive: Receive = {
    case msg =>
      stash()
      pipe(storage.getScreening(imdbId, screenId)).to(self, sender)
      context.become(reading)
  }

  def registering(rs: RegisterScreening): Receive = {
    case Some(screening: Screening) =>
      sender ! None
      context.become(registered(screening))
      unstashAll()
    case None =>
      context.become(unregistered)
      unstashAll()
    case f: akka.actor.Status.Failure =>
      context.unbecome()
  }

  def reading: Receive = {
    case Some(screening: Screening) =>
      context.become(registered(screening))
      unstashAll()
    case None =>
      context.become(unregistered)
      unstashAll()
    case f: akka.actor.Status.Failure =>
      context.unbecome()

    case _ => stash()
  }

  def registered(screening: Screening): Receive = {
    case rs: RegisterScreening => sender ! None
  }

  def unregistered: Receive = {
    case rs: RegisterScreening =>
      log.info("Reserver.unregistered")
      log.info(sender.toString())
      val screening = Screening(rs.imdbId, rs.screenId, "NA", rs.availableSeats, 0)
      pipe(storage.registerScreening(screening)).to(self, sender)
    case s: Screening =>
      context.become(registered(s))
      sender ! Some(s)
  }
}

object Manager {
  def props(storage: Storage): Props = Props(new Manager(storage))
}

class Manager(storage: Storage) extends Actor {
  val log = Logging(context.system, this)

  private def getReserver(imdbId: String, screenId: String) = {
    val name = s"$imdbId-$screenId"
    context.child(name).getOrElse {
      context.actorOf(Reserver.props(imdbId, screenId, storage), name)
    }
  }

  def receive: Receive = {
    case rs: RegisterScreening =>
      log.info("Manager.receive")
      log.info(sender.toString())
      getReserver(rs.imdbId, rs.screenId).forward(rs)
  }
}

object Boot extends App with JsonSupport {
  implicit val system = ActorSystem("mrs")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val storage = new MongoStorage()
  val manager = system.actorOf(Manager.props(storage))

  implicit val timeout = Timeout(5.seconds)

  val route =
    path("health") {
      get {
        complete(StatusCodes.OK)
      }
    } ~
    path("screenings") {
      post {
        entity(as[RegisterScreening]) { rs =>
          onComplete(ask(manager, rs).mapTo[Option[Screening]]) {
            case Success(Some(screening: Screening)) =>
              complete(screening)
            case Success(None) =>
              complete(StatusCodes.BadRequest, "screening already registered")
            case Failure(e) =>
              println(e.getMessage)
              complete(StatusCodes.InternalServerError)
          }
        }
      }
    }

  Http().bindAndHandle(route, "0.0.0.0", 9000)
}