# UTM Backup Manager 1.5.1

Version 1.5.1 fixes some frontend issues, which bugged me during the use of the app.

## Highlights
- Fixed version number bug, where development version was shown when starting the application not by "java -jar" but with 
"mvn spring-boot:run". The version number is now correctly extracted from the pom.xml file.
- When selecting VMs in the dialog to create a backup, the list now shows a badge for VMs that have changed since the 
last backup.
- During a running backup, a badge next to the VM name shows the provider, from which the VM comes
- While the remaining time for the running backup is being calculated, the status now shows "Calculating..." instead of 
the confusing placeholder "--:--".
- When a card for an existing backup in a column further to the right is being marked as "Stable" the page is not being 
reloaded anymore, and the app remembers the currently selected column. 
- The JavaScript and CSS files are now references with the current version number to avoid problems with cached files
after installing a new version.

## Quality

The release was verified with the complete Maven build and automated test suite on the final `main` branch.
