package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.config.VmProperties;
import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.provider.FakeVmProvider;
import de.fjordkommission.utmbackup.provider.VmProviderRegistry;
import de.fjordkommission.utmbackup.service.VmInventory;
import de.fjordkommission.utmbackup.service.VmSizeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VmControllerTest {

    private final LocalVm utmArch = new LocalVm(
            "ArchLinux 02.27.utm",
            "ArchLinux 02.27",
            List.of(Path.of("/vms/utm/ArchLinux 02.27.utm"))
    );

    private final LocalVm qemuArch = new LocalVm(
            "ArchLinux",
            "ArchLinux",
            List.of(Path.of("/vms/qemu/ArchLinux"))
    );

    private final VmSizeService sizeService = mock(VmSizeService.class);

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new VmController(inventory(), sizeService)
    ).build();

    @Test
    void canonicalIdWithSpacesAndDotsResolvesToItsVm() throws Exception {
        when(sizeService.sizeOf(utmArch)).thenReturn(4096L);

        mockMvc.perform(get("/vm/{vmId}/size", "utm:ArchLinux 02.27.utm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vmId").value("utm:ArchLinux 02.27.utm"))
                .andExpect(jsonPath("$.bytes").value(4096));

        verify(sizeService).sizeOf(utmArch);
    }

    @Test
    void sameLocalIdInTwoProvidersAddressesTwoDistinctVms() throws Exception {
        when(sizeService.sizeOf(qemuArch)).thenReturn(2048L);

        mockMvc.perform(get("/vm/{vmId}/size", "qemu:ArchLinux"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vmId").value("qemu:ArchLinux"))
                .andExpect(jsonPath("$.bytes").value(2048));

        verify(sizeService).sizeOf(qemuArch);
        verify(sizeService, never()).sizeOf(utmArch);
    }

    @Test
    void localIdAloneIsNotEnoughToResolveAVm() {
        // "ArchLinux" without a provider is malformed and must not match either VM.
        assertVmNotFound("ArchLinux");

        verifyNoInteractions(sizeService);
    }

    @Test
    void sizeIsNeverReadForVmFromAnotherProvider() {
        assertVmNotFound("freebsd:ArchLinux");

        verifyNoInteractions(sizeService);
    }

    private void assertVmNotFound(String vmId) {
        RequestBuilder request = get("/vm/{vmId}/size", vmId);

        Exception exception = assertThrows(
                Exception.class,
                () -> mockMvc.perform(request)
        );

        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertEquals("VM not found: " + vmId, exception.getCause().getMessage());
    }

    private static VmInventory inventory() {
        FakeVmProvider utm = FakeVmProvider.of(
                "utm",
                List.of(
                        new LocalVm(
                                "ArchLinux 02.27.utm",
                                "ArchLinux 02.27",
                                List.of(Path.of("/vms/utm/ArchLinux 02.27.utm"))
                        )
                ),
                Map.of("ArchLinux 02.27.utm", VmStatus.STOPPED)
        );
        FakeVmProvider qemu = FakeVmProvider.of(
                "qemu",
                List.of(new LocalVm("ArchLinux", "ArchLinux", List.of(Path.of("/vms/qemu/ArchLinux")))),
                Map.of("ArchLinux", VmStatus.STOPPED)
        );

        return new VmInventory(new VmProviderRegistry(
                List.of(utm, qemu),
                new VmProperties(List.of("utm", "qemu"), null, null)
        ));
    }
}
