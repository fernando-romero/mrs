package com.romero.http

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
