package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.service.VmSizeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VmController {

    private final VmProviderRegistry vmProviderRegistry;
    private final VmSizeService vmSizeService;

    public VmController(
            VmProviderRegistry vmProviderRegistry,
            VmSizeService vmSizeService
    ) {
        this.vmProviderRegistry = vmProviderRegistry;
        this.vmSizeService = vmSizeService;
    }

    @GetMapping("/vm/{vmId}/size")
    VmSizeResponse vmSize(@PathVariable String vmId) {
        LocalVm vm = vmProviderRegistry.current()
                .findAll()
                .stream()
                .filter(candidate -> candidate.id().equals(vmId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "VM not found: " + vmId
                ));

        return new VmSizeResponse(
                vm.id(),
                vmSizeService.sizeOf(vm)
        );
    }

    record VmSizeResponse(
            String vmId,
            long bytes
    ) {
    }
}