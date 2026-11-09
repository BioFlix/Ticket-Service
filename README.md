# ticket-service

Udsteder billetter med QR-kode (én pr. sæde) når en booking bekræftes. Port **8085**, MongoDB `tickets_db`.
QR-billedet gemmes indtil videre i MongoDB (S3 er "not yet wired" i diagrammet).

## Endpoints
| Metode | Sti | Beskrivelse |
|---|---|---|
| GET | `/api/tickets/me` | Mine billetter (`X-User-Id`) |
| GET | `/api/tickets/booking/{bookingId}` | Billetter for en booking |
| GET | `/api/tickets/{id}` | Én billet |
| GET | `/api/tickets/{id}/qr` | QR-kode som PNG (kan bruges direkte i `<img>`) |
| POST | `/api/tickets/{id}/scan` | Scanning ved indgangen → `USED` |

## Events (når RabbitMQ-repoet er klar)
Skal lytte på `booking.confirmed` (→ `issueTickets`) og `booking.cancelled` (→ `cancelTickets`). Skal publicere `ticket.issued`.
