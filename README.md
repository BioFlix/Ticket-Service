# ticket-service

Udsteder billetter med QR-kode (én pr. sæde) når en booking bekræftes. Port **8085**, MongoDB `tickets_db`, RabbitMQ.
QR-billedet gemmes indtil videre i MongoDB (S3 er "not yet wired" i diagrammet).

## Endpoints
Headerne `X-User-Id` (påkrævet) og `X-User-Role` (valgfri) sættes af gateway'en.
Brugere ser kun deres egne billetter – andres giver 404. `STAFF`/`ADMIN` ser alle.

| Metode | Sti | Beskrivelse |
|---|---|---|
| GET | `/api/tickets/me` | Mine billetter |
| GET | `/api/tickets/booking/{bookingId}` | Billetter for en booking (ejer eller staff) |
| GET | `/api/tickets/{id}` | Én billet (ejer eller staff) |
| GET | `/api/tickets/{id}/qr` | QR-kode som PNG (ejer eller staff) |
| POST | `/api/tickets/{id}/scan` | Scanning ved indgangen → `USED`. Kun `X-User-Role: STAFF`/`ADMIN`, ellers 403 |

Billetter oprettes og annulleres **kun** via events – der er ingen REST-endpoint til det.

## Events
Topic exchange `bioflix.events` (JSON). Ukendte felter ignoreres, så booking-service gerne må sende mere.

**Lytter på `booking.confirmed`** (kø `ticket-service.booking.confirmed`) → udsteder billetter:
```json
{ "bookingId": 42, "userId": 7, "showtimeId": "abc", "movieTitle": "Dune",
  "hallName": "Sal 1", "startTime": "2026-12-01T19:00:00Z", "seats": ["C7", "C8"] }
```

**Lytter på `booking.cancelled`** (kø `ticket-service.booking.cancelled`) → annullerer billetterne:
```json
{ "bookingId": 42, "reason": "Cancelled by customer" }
```

**Publicerer `ticket.issued`** når billetterne for en booking findes (til notification-service):
```json
{ "bookingId": 42, "userId": 7, "showtimeId": "abc", "movieTitle": "Dune", "hallName": "Sal 1",
  "startTime": "2026-12-01T19:00:00Z",
  "tickets": [ { "ticketId": "6735…", "seat": "C7", "qrUrl": "/api/tickets/6735…/qr" } ] }
```
Sendes "at-least-once" – ved genlevering af `booking.confirmed` kan den komme igen.

## Regler
- **Ét aktivt billet pr. sæde pr. forestilling.** Sikret af et unikt partial index på `seatKey` (`showtimeId|sæde`).
- **Annullerede sæder kan bookes igen.** En annulleret billet mister sin `seatKey`, så en ny booking kan få billet til samme sæde. Brugte (`USED`) billetter røres ikke ved annullering.
- **Idempotent.** Samme `booking.confirmed` to gange giver ikke dobbelte billetter; mangler nogle sæder (fx efter et crash), udstedes kun de manglende.
- **Rækkefølge.** Annullerede bookinger huskes i `cancelled_bookings`, så et `booking.confirmed` der kommer *efter* `booking.cancelled` ikke udsteder billetter.
- **Fejl.** Er et sæde stadig optaget af en anden booking, prøves beskeden igen 5 gange (2s → 20s backoff) og havner derefter i `ticket-service.dead-letter`.

## Konfiguration
| Env | Default |
|---|---|
| `MONGO_HOST` | `localhost` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest` |

## Kør lokalt
Servicen skal bruge MongoDB og RabbitMQ (uden RabbitMQ starter den, men logger `Connection refused` hele tiden):
```bash
docker run -d --name bioflix-rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
mvn spring-boot:run
```
Åbn **http://localhost:8085/swagger-ui.html** – her kan du udfylde `X-User-Id`/`X-User-Role` og prøve alle endpoints.
En almindelig browser-fane kan ikke sende headerne, så `/api/tickets/me` direkte i adresselinjen giver 401.
RabbitMQ's admin-side: http://localhost:15672 (guest/guest).

## Testdata
Kør med profilen `seed` for at lægge demo-billetter i MongoDB (springes over hvis de findes i forvejen):
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=seed
```
| Booking | Bruger | Film | Sæder | Status |
|---|---|---|---|---|
| 1001 | 1 | Dune: Part Two (i morgen) | C7, C8 | `VALID` |
| 1002 | 2 | Oppenheimer (i går) | A1 | `USED` |
| 1003 | 1 | Interstellar (om en uge) | F5 | `CANCELLED` |
| 1004 | 3 | Interstellar (om en uge) | F5, F6 | `VALID` – samme sæde F5 genbooket |

Se dem fx med `curl -H "X-User-Id: 1" localhost:8085/api/tickets/me`.

## Tests
`mvn test` – integrationstesten starter MongoDB og RabbitMQ med Testcontainers, så Docker skal køre.
