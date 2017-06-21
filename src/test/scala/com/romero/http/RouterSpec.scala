package com.romero.http

import akka.actor.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.Specs2RouteTest
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.ByteString
import com.romero.actor.{NoSeatsAvailable, ScreeningAlreadyRegistered}
import com.romero.service.PersistenceService
import org.specs2.mutable.Specification
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.Future

class RouterSpec extends Specification with Specs2RouteTest with SprayJsonSupport with DefaultJsonProtocol {

  implicit val registerScreeningFmt: RootJsonFormat[RegisterScreening] = jsonFormat3(RegisterScreening)
  implicit val reserveSeatFmt: RootJsonFormat[ReserveSeat] = jsonFormat2(ReserveSeat)
  implicit val screeningFmt: RootJsonFormat[Screening] = jsonFormat5(Screening)

  def getRouter(probe: TestProbe) = new Router {
    override val persistenceService = new PersistenceService {

      override def updateScreening(screening: Screening): Future[Unit] = {
        (probe.ref ? screening).mapTo[Unit]
      }

      override def getScreening(imdbId: String, screenId: String): Future[Option[Screening]] = {
        (probe.ref ? (imdbId, screenId)).mapTo[Option[Screening]]
      }

      override def registerScreening(screening: Screening): Future[Unit] = {
        println(screening)
        (probe.ref ? screening).mapTo[Unit]
      }

      override def getScreenings: Future[Seq[Screening]] = {
        (probe.ref ? 'getScreenings).mapTo[Seq[Screening]]
      }
    }
    override val manager: ActorRef = probe.ref
  }

  "GET to /health" should {
    "return 200" in {
      val router = getRouter(TestProbe())
      Get("/health") ~> router.route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }
  }

  "POST to /screenings" should {
    "return 200, screening object and location header when the data is correct and screening not registered" in {
      val probe = TestProbe()
      val router = getRouter(probe)

      val jsonRequest = ByteString(
        s"""
           |{
           |    "imdbId": "tt0111161",
           |    "screenId": "screen_123459",
           |    "availableSeats": 100
           |}
        """.stripMargin)

      val postRequest = HttpRequest(
        HttpMethods.POST,
        uri = "/screenings",
        entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

      postRequest ~> router.route ~> check {
        val rs = probe.expectMsgType[RegisterScreening]
        val screening = Screening(rs.imdbId, rs.screenId, "NA", rs.availableSeats, 0)
        probe.reply(screening)
        eventually {
          status shouldEqual StatusCodes.Created
          header("Location") should beSome(RawHeader("Location", s"/movies/${rs.imdbId}/screenings/${rs.screenId}"))
          responseAs[Screening] shouldEqual(screening)
        }
      }
    }

    "return 400 when data is incorrect" in {
      val probe = TestProbe()
      val router = getRouter(probe)

      val jsonRequest = ByteString(
        s"""
           |{
           |    "foo": "tt0111161",
           |    "bar": "screen_123459",
           |    "baz": 100
           |}
        """.stripMargin)

      val postRequest = HttpRequest(
        HttpMethods.POST,
        uri = "/screenings",
        entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

      postRequest ~> Route.seal(router.route) ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

    "return 403 when screening is already registered" in {
      val probe = TestProbe()
      val router = getRouter(probe)

      val jsonRequest = ByteString(
        s"""
           |{
           |    "imdbId": "tt0111161",
           |    "screenId": "screen_123459",
           |    "availableSeats": 100
           |}
        """.stripMargin)

      val postRequest = HttpRequest(
        HttpMethods.POST,
        uri = "/screenings",
        entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

      postRequest ~> router.route ~> check {
        probe.expectMsgType[RegisterScreening]
        probe.reply(ScreeningAlreadyRegistered)
        eventually {
          status shouldEqual StatusCodes.Forbidden
        }
      }
    }
  }

  "PUT to /screenings" should {
    "return 200 and screening object when the data is correct there are seats available" in {
      val probe = TestProbe()
      val router = getRouter(probe)

      val jsonRequest = ByteString(
        s"""
           |{
           |    "imdbId": "tt0111161",
           |    "screenId": "screen_123459"
           |}
        """.stripMargin)

      val putRequest = HttpRequest(
        HttpMethods.PUT,
        uri = "/screenings",
        entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

      putRequest ~> router.route ~> check {
        val rs = probe.expectMsgType[ReserveSeat]
        val screening = Screening(rs.imdbId, rs.screenId, "NA", 99, 1)
        probe.reply(screening)
        eventually {
          status shouldEqual StatusCodes.OK
          responseAs[Screening] shouldEqual(screening)
        }
      }
    }

    "return 400 when data is incorrect" in {
      val probe = TestProbe()
      val router = getRouter(probe)

      val jsonRequest = ByteString(
        s"""
           |{
           |    "foo": "tt0111161",
           |    "bar": "screen_123459"
           |}
        """.stripMargin)

      val putRequest = HttpRequest(
        HttpMethods.PUT,
        uri = "/screenings",
        entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

      putRequest ~> Route.seal(router.route) ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

    "return 403 when there are not seats available" in {
      val probe = TestProbe()
      val router = getRouter(probe)

      val jsonRequest = ByteString(
        s"""
           |{
           |    "imdbId": "tt0111161",
           |    "screenId": "screen_123459"
           |}
        """.stripMargin)

      val putRequest = HttpRequest(
        HttpMethods.PUT,
        uri = "/screenings",
        entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

      putRequest ~> router.route ~> check {
        probe.expectMsgType[ReserveSeat]
        probe.reply(NoSeatsAvailable)
        eventually {
          status shouldEqual StatusCodes.Forbidden
        }
      }
    }
  }

  "GET to /screenings" should {
    "return 200 and all the screenings" in {
      val probe = TestProbe()
      val router = getRouter(probe)

      Get("/screenings") ~> router.route ~> check {
        probe.expectMsg('getScreenings)
        val s1 = Screening("foo", "bar", "baz", 100, 0)
        val screenings = Seq(s1, s1.copy(imdbId = "foo2"))
        probe.reply(screenings)
        eventually {
          status shouldEqual StatusCodes.OK
          responseAs[Seq[Screening]] shouldEqual screenings
        }
      }
    }
  }

  "GET to /movies/:imdbId/screenings/:screenId" should {
    "return 200 and the screening if exists" in {
      val probe = TestProbe()
      val router = getRouter(probe)

      Get("/movies/foo/screenings/bar") ~> router.route ~> check {
        probe.expectMsg(("foo", "bar"))
        val screening = Screening("foo", "bar", "baz", 100, 0)
        probe.reply(Some(screening))
        eventually {
          status shouldEqual StatusCodes.OK
          responseAs[Screening] shouldEqual screening
        }
      }
    }

    "return 404 if the screening does not exists" in {
      val probe = TestProbe()
      val router = getRouter(probe)

      Get("/movies/foo/screenings/bar") ~> router.route ~> check {
        probe.expectMsg(("foo", "bar"))
        probe.reply(None)
        eventually {
          status shouldEqual StatusCodes.NotFound
        }
      }
    }
  }
}
