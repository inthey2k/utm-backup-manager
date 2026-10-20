package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.service.VmInventory;
import de.fjordkommission.utmbackup.service.VmSizeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VmController {

    private final VmInventory vmInventory;
    private final VmSizeService vmSizeService;

    public VmController(
            VmInventory vmInventory,
            VmSizeService vmSizeService
    ) {
        this.vmInventory = vmInventory;
        this.vmSizeService = vmSizeService;
    }

    /**
     * Returns the backup size of a VM addressed by its canonical id
     * ({@code providerId:localId}).
     */
    @GetMapping("/vm/{vmId}/size")
    VmSizeResponse vmSize(@PathVariable String vmId) {
        ManagedVm vm = VmId.tryParse(vmId)
                .flatMap(vmInventory.build()::find)
                .orElseThrow(() -> new IllegalArgumentException(
                        "VM not found: " + vmId
                ));

        return new VmSizeResponse(
                vm.id().toString(),
                vmSizeService.sizeOf(vm.localVm())
        );
    }

    record VmSizeResponse(
            String vmId,
            long bytes
    ) {
    }
}
