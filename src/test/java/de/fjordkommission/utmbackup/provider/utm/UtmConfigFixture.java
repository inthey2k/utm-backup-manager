package de.fjordkommission.utmbackup.provider.utm;

/**
 * Builds {@code config.plist} files shaped like those of UTM 4.x: the VM identity is the
 * {@code UUID} in the {@code Information} dictionary, and the drive identifiers are UUIDs
 * too, so a parser that reads the wrong key is caught.
 */
public final class UtmConfigFixture {

    private UtmConfigFixture() {
    }

    /** A complete configuration for a VM with the given display name and UUID. */
    public static String config(String name, String uuid) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                \t<key>Backend</key>
                \t<string>QEMU</string>
                \t<key>ConfigurationVersion</key>
                \t<integer>4</integer>
                \t<key>Drive</key>
                \t<array>
                \t\t<dict>
                \t\t\t<key>Identifier</key>
                \t\t\t<string>0F3B4E0A-1C2D-4E5F-8A9B-0C1D2E3F4A5B</string>
                \t\t\t<key>ImageName</key>
                \t\t\t<string>disk.qcow2</string>
                \t\t\t<key>ImageType</key>
                \t\t\t<string>disk</string>
                \t\t\t<key>Interface</key>
                \t\t\t<string>virtio</string>
                \t\t</dict>
                \t</array>
                \t<key>Information</key>
                \t<dict>
                \t\t<key>Icon</key>
                \t\t<string>linux</string>
                \t\t<key>IconCustom</key>
                \t\t<false/>
                \t\t<key>Name</key>
                \t\t<string>%s</string>
                \t\t<key>UUID</key>
                \t\t<string>%s</string>
                \t</dict>
                </dict>
                </plist>
                """.formatted(name, uuid);
    }
}
