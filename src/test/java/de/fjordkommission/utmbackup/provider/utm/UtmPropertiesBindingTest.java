package de.fjordkommission.utmbackup.provider.utm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UtmPropertiesBindingTest {

    @Test
    void iconDirectoryOverrideIsBoundFromVmUtmIconsDirectory() {
        UtmProperties properties = bind(Map.of(
                "vm.utm.directory", "/vms",
                "vm.utm.cli", "/utmctl",
                "vm.utm.icons.directory", "/Applications/UTM.app/Contents/Resources/Icons"
        ));

        assertEquals(
                Path.of("/Applications/UTM.app/Contents/Resources/Icons"),
                properties.iconsDirectory()
        );
    }

    @Test
    void iconDirectoryIsOptional() {
        UtmProperties properties = bind(Map.of(
                "vm.utm.directory", "/vms",
                "vm.utm.cli", "/utmctl"
        ));

        assertNull(properties.iconsDirectory());
    }

    private static UtmProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("vm.utm", UtmProperties.class)
                .get();
    }
}
