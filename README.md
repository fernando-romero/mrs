# Movie Reservation System

akka-http example app.

## Requirements to run

1. Sbt installed.
2. MongoDB running. It connects to a local instance by default, this can be configured with the `mongoUri` setting in application.conf.

## Run application

```
sbt run
```

Application will be available at [http://localhost:9000](http://localhost:9000). Interface and port are configurable.

## Endpoints

### GET /health

Gets service health.

Responses:

| Code     | Meaning																																				|
|----------|------------------|
| 200 (OK) | Healthy service. |

### POST /screenings

Register a movie screening.

Responses:

| Code                        | Meaning																																				|
|-----------------------------|----------------------------------------------------------------------------|
| 201 (Created)               | The screening was successfully registered. A screening object is returned. |
| 400 (Bad request)           | Bad formatted request.                                                     |
| 403 (Forbidden)             | Screening already registered.                                              |
| 500 (Internal Server Error) | Something unexpected happened at server side.                              |

Example request:

```
{
 	"imdbId": "tt0111161",
    "availableSeats": 3,
    "screenId": "screen_123456"
 }
```

Example response:

`{
     "reservedSeats": 0,
     "screenId": "screen_123456",
     "imdbId": "tt0111161",
     "availableSeats": 3,
     "movieTitle": "NA"
 }`

### PUT /screenings

Reserves a set for a screening

| Code                        | Meaning																																				           |
|-----------------------------|-----------------------------------------------|
| 200 (OK)                    | Seat reserved.                                |
| 400 (Bad request)           | Bad formatted request.                        |
| 403 (Forbidden)             | Seats not available.                          |
| 404 (Not found)             | Screening not found.                          |
| 500 (Internal Server Error) | Something unexpected happened at server side. |

Example request:

`{
    "imdbId": "tt0111161",
    "screenId": "screen_123456"
 }`

Example response:

`{
     "reservedSeats": 1,
     "screenId": "screen_123456",
     "imdbId": "tt0111161",
     "availableSeats": 2,
     "movieTitle": "NA"
 }`

### GET /screenings

Gets all registered screenings

| Code                        | Meaning																																				           |
|-----------------------------|-----------------------------------------------|
| 200 (OK)                    | Success.                                      |
| 500 (Internal Server Error) | Something unexpected happened at server side. |

Example response:

`[
     {
         "reservedSeats": 0,
         "screenId": "screen_123457",
         "imdbId": "tt0111161",
         "availableSeats": 3,
         "movieTitle": "NA"
     },
     {
         "reservedSeats": 3,
         "screenId": "screen_123456",
         "imdbId": "tt0111161",
         "availableSeats": 0,
         "movieTitle": "NA"
     }
 ]`

 ### GET /movies/:imdbId/screenings/:screenId

 Gets a screening

 | Code                        | Meaning																																				           |
 |-----------------------------|-----------------------------------------------|
 | 200 (OK)                    | Success.                                      |
 | 404 (Not found)             | Screening not found.                          |
 | 500 (Internal Server Error) | Something unexpected happened at server side. |

Example response:

`{
     "reservedSeats": 1,
     "screenId": "screen_123456",
     "imdbId": "tt0111161",
     "availableSeats": 2,
     "movieTitle": "NA"
 }`