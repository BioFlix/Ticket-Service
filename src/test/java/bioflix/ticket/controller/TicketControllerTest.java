package bioflix.ticket.controller;

import bioflix.ticket.dto.ScanRequest;
import bioflix.ticket.dto.TicketResponse;
import bioflix.ticket.entity.TicketSeat;
import bioflix.ticket.entity.TicketStatus;
import bioflix.ticket.exception.ForbiddenException;
import bioflix.ticket.exception.InvalidQrTokenException;
import bioflix.ticket.exception.NotFoundException;
import bioflix.ticket.security.Caller;
import bioflix.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketController.class)
class TicketControllerTest {

    private static final String ALICE = "11111111-1111-4111-8111-111111111111";
    private static final String ADMIN_ID = "99999999-9999-4999-8999-999999999999";

    @Autowired MockMvc mvc;
    @MockitoBean TicketService ticketService;

    private static TicketResponse ticket(TicketStatus status) {
        return new TicketResponse("t1", "TK-7F3K-92QD", "b1", "show-1", "Dune", "Sal 1",
                Instant.parse("2026-12-01T19:00:00Z"), new TicketSeat("F", 7, "STANDARD"),
                status, "/api/tickets/t1/qr", Instant.now(), null, null);
    }

    @Test
    void myTicketsRequiresUserHeader() throws Exception {
        mvc.perform(get("/api/tickets/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void myTickets() throws Exception {
        when(ticketService.getByUser(ALICE)).thenReturn(List.of(ticket(TicketStatus.VALID)));
        mvc.perform(get("/api/tickets/me").header("X-User-Id", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticketNumber").value("TK-7F3K-92QD"))
                .andExpect(jsonPath("$[0].seat.row").value("F"))
                .andExpect(jsonPath("$[0].seat.number").value(7))
                .andExpect(jsonPath("$[0].qrUrl").value("/api/tickets/t1/qr"))
                .andExpect(jsonPath("$[0].qrToken").doesNotExist());
    }

    @Test
    void otherUsersBookingIs404() throws Exception {
        when(ticketService.getByBooking("b1", new Caller("someone-else", null)))
                .thenThrow(new NotFoundException("Booking b1 not found"));
        mvc.perform(get("/api/tickets/booking/b1").header("X-User-Id", "someone-else"))
                .andExpect(status().isNotFound());
    }

    @Test
    void qrIsPng() throws Exception {
        when(ticketService.getQrCode("t1", new Caller(ALICE, null))).thenReturn(new byte[]{1, 2});
        mvc.perform(get("/api/tickets/t1/qr").header("X-User-Id", ALICE))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void scanTakesTokenInBody() throws Exception {
        when(ticketService.scan(new ScanRequest("tok", "show-1"), new Caller(ADMIN_ID, "ADMIN")))
                .thenReturn(ticket(TicketStatus.SCANNED));
        when(ticketService.scan(new ScanRequest("tok", null), new Caller(ALICE, null)))
                .thenThrow(new ForbiddenException("Only admins"));
        when(ticketService.scan(new ScanRequest("forged", null), new Caller(ADMIN_ID, "ADMIN")))
                .thenThrow(new InvalidQrTokenException());

        mvc.perform(post("/api/tickets/scan").header("X-User-Id", ADMIN_ID).header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"tok\",\"showtimeId\":\"show-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNED"));
        mvc.perform(post("/api/tickets/scan").header("X-User-Id", ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"tok\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/scan").header("X-User-Id", ADMIN_ID).header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"forged\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tickets/scan").header("X-User-Id", ADMIN_ID).header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
