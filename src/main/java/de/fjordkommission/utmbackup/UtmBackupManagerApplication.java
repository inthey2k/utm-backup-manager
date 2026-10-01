package de.fjordkommission.utmbackup;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import de.fjordkommission.utmbackup.config.BackupProperties;
import de.fjordkommission.utmbackup.config.VmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        BackupProperties.class,
        VmProperties.class
})
public class UtmBackupManagerApplication {
 public static void main(String[] args) { SpringApplication.run(UtmBackupManagerApplication.class,args); }
}
