package bioflix.ticket.controller;

import bioflix.ticket.dto.TicketResponse;
import bioflix.ticket.entity.TicketStatus;
import bioflix.ticket.exception.ForbiddenException;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketController.class)
class TicketControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TicketService ticketService;

    private static TicketResponse ticket(TicketStatus status) {
        return new TicketResponse("t1", 1L, "Dune", "Sal 1", Instant.parse("2026-12-01T19:00:00Z"), "C7",
                status, "/api/tickets/t1/qr", Instant.now(), null, null);
    }

    @Test
    void myTicketsRequiresUserHeader() throws Exception {
        mvc.perform(get("/api/tickets/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void myTickets() throws Exception {
        when(ticketService.getByUser(10L)).thenReturn(List.of(ticket(TicketStatus.VALID)));
        mvc.perform(get("/api/tickets/me").header("X-User-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seat").value("C7"))
                .andExpect(jsonPath("$[0].qrUrl").value("/api/tickets/t1/qr"));
    }

    @Test
    void otherUsersBookingIs404() throws Exception {
        when(ticketService.getByBooking(eq(1L), eq(new Caller(20L, null))))
                .thenThrow(new NotFoundException("Booking 1 not found"));
        mvc.perform(get("/api/tickets/booking/1").header("X-User-Id", "20"))
                .andExpect(status().isNotFound());
    }

    @Test
    void qrIsPng() throws Exception {
        when(ticketService.getQrCode("t1", new Caller(10L, null))).thenReturn(new byte[]{1, 2});
        mvc.perform(get("/api/tickets/t1/qr").header("X-User-Id", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void scanPassesRoleAndMapsForbidden() throws Exception {
        when(ticketService.scan("t1", new Caller(10L, null))).thenThrow(new ForbiddenException("Only staff"));
        when(ticketService.scan("t1", new Caller(99L, "STAFF"))).thenReturn(ticket(TicketStatus.USED));

        mvc.perform(post("/api/tickets/t1/scan").header("X-User-Id", "10"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/t1/scan").header("X-User-Id", "99").header("X-User-Role", "STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("USED"));
    }
}
