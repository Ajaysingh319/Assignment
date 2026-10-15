package com.assignment.fulfilment.inventory.api;

import com.assignment.fulfilment.inventory.InventoryReservationService;
import com.assignment.fulfilment.inventory.dto.ReservationResponse;
import com.assignment.fulfilment.inventory.dto.ReserveInventoryRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/reservations")
public class InventoryReservationController {

    private final InventoryReservationService reservationService;

    public InventoryReservationController(InventoryReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@Valid @RequestBody ReserveInventoryRequest request) {
        return reservationService.reserve(request);
    }

    @PostMapping("/{orderId}/cancel")
    public ReservationResponse cancel(@PathVariable("orderId") UUID orderId) {
        return reservationService.cancel(orderId);
    }

    @PostMapping("/{orderId}/fulfil")
    public ReservationResponse fulfil(@PathVariable("orderId") UUID orderId) {
        return reservationService.fulfil(orderId);
    }

    @GetMapping("/{orderId}")
    public ReservationResponse getReservations(@PathVariable("orderId") UUID orderId) {
        return reservationService.getReservations(orderId);
    }
}
