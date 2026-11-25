package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.service.VmInventory;
import de.fjordkommission.utmbackup.service.VmSizeService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Optional;

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

    /**
     * Returns the custom icon image of a VM addressed by its canonical id. The file is the one
     * the provider configured, so the request never supplies a path. A VM without a custom icon,
     * or whose file is not an image, answers 404 and the page shows the fallback icon.
     */
    @GetMapping("/vm/{vmId}/icon")
    ResponseEntity<Resource> vmIcon(@PathVariable String vmId) {
        Optional<Path> icon = VmId.tryParse(vmId)
                .flatMap(vmInventory.build()::find)
                .flatMap(vmInventory::iconFile);

        if (icon.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Optional<MediaType> mediaType = MediaTypeFactory.getMediaType(icon.get().getFileName().toString())
                .filter(type -> "image".equals(type.getType()));

        if (mediaType.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(mediaType.get())
                .cacheControl(CacheControl.noCache())
                .body(new FileSystemResource(icon.get()));
    }

    record VmSizeResponse(
            String vmId,
            long bytes
    ) {
    }
}
