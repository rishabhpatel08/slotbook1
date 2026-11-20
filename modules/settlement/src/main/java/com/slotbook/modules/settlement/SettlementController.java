package com.slotbook.modules.settlement;

import com.slotbook.contract.CurrentMember;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class SettlementController {

    private final CurrentMember current;
    private final SettlementService settlementService;

    public SettlementController(CurrentMember current, SettlementService settlementService) {
        this.current = current;
        this.settlementService = settlementService;
    }

    @PostMapping("/api/modules/settlement/admin/settle")
    public SettlementModels.SettleResponse settle() {
        current.requireAdmin();
        Long batchId = settlementService.settle();
        return new SettlementModels.SettleResponse(batchId);
    }

    @GetMapping("/api/modules/settlement/admin/batches")
    public List<SettlementModels.BatchSummary> listBatches() {
        current.requireAdmin();
        return settlementService.listBatches();
    }

    @GetMapping("/api/modules/settlement/admin/batches/{id}")
    public SettlementModels.BatchDetail getBatch(@PathVariable long id) {
        current.requireAdmin();
        return settlementService.getBatch(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch " + id + " not found"));
    }
}
