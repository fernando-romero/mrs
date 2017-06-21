package com.romero.http

import akka.actor.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.romero.actor.{NoSeatsAvailable, ScreeningAlreadyRegistered, ScreeningNotFound}
import com.romero.service.PersistenceService
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

trait Router extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val registerScreeningFmt: RootJsonFormat[RegisterScreening] = jsonFormat3(RegisterScreening)
  implicit val reserveSeatFmt: RootJsonFormat[ReserveSeat] = jsonFormat2(ReserveSeat)
  implicit val screeningFmt: RootJsonFormat[Screening] = jsonFormat5(Screening)

  implicit val timeout = Timeout(5.seconds)

  val manager: ActorRef
  val persistenceService: PersistenceService

  val route: Route =
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
        onComplete(persistenceService.getScreenings) {
          case Success(screenings: Seq[Screening]) =>
            complete(screenings)
          case Failure(t) =>
            complete(StatusCodes.InternalServerError, t.getMessage)
        }
      }
    } ~
    path("movies" / Segment / "screenings" / Segment)  { (imdbId, screenId) =>
      onComplete(persistenceService.getScreening(imdbId, screenId)) {
        case Success(Some(s)) =>
          complete(s)
        case Success(None) =>
          complete(StatusCodes.NotFound)
        case Failure(t) =>
          complete(StatusCodes.InternalServerError, t.getMessage)
      }
    }
}
