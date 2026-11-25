package de.fjordkommission.utmbackup.controller;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmStatus;
import de.fjordkommission.utmbackup.service.VmInventory;
import de.fjordkommission.utmbackup.service.VmSizeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VmIconControllerTest {

    private static final VmId VM = new VmId("utm", "Linux.utm");

    @TempDir
    Path tempDirectory;

    private final VmInventory inventory = mock(VmInventory.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new VmController(inventory, mock(VmSizeService.class)))
            .build();

    @BeforeEach
    void knownVm() {
        ManagedVm vm = new ManagedVm(
                VM,
                "UTM",
                new LocalVm("Linux.utm", "Linux", List.of(tempDirectory.resolve("Linux.utm"))),
                VmStatus.STOPPED
        );

        when(inventory.build()).thenReturn(new VmInventory.Inventory(List.of(), List.of(vm)));
    }

    @Test
    void customIconOfTheVmIsServedWithItsImageType() throws Exception {
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G'};
        Path icon = Files.write(tempDirectory.resolve("icon.png"), bytes);
        when(inventory.iconFile(any())).thenReturn(Optional.of(icon));

        mockMvc.perform(get("/vm/{vmId}/icon", VM.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(result -> assertArrayEquals(bytes, result.getResponse().getContentAsByteArray()));
    }

    @Test
    void vmWithoutCustomIconAnswersNotFoundSoThePageUsesTheFallback() throws Exception {
        when(inventory.iconFile(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/vm/{vmId}/icon", VM.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void unknownVmAnswersNotFound() throws Exception {
        mockMvc.perform(get("/vm/{vmId}/icon", "utm:Missing.utm"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileThatIsNotAnImageIsNeverServed() throws Exception {
        Path notImage = Files.writeString(tempDirectory.resolve("config.txt"), "secret");
        when(inventory.iconFile(any())).thenReturn(Optional.of(notImage));

        mockMvc.perform(get("/vm/{vmId}/icon", VM.toString()))
                .andExpect(status().isNotFound());
    }
}
