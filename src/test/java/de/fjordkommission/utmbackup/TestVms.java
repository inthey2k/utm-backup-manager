package de.fjordkommission.utmbackup;

import de.fjordkommission.utmbackup.model.LocalVm;
import de.fjordkommission.utmbackup.model.ManagedVm;
import de.fjordkommission.utmbackup.model.VmId;
import de.fjordkommission.utmbackup.model.VmStatus;

/**
 * Builds the aggregated VMs that the backup writer and job service receive.
 */
public final class TestVms {

    private TestVms() {
    }

    /** A STOPPED UTM VM, the state every backup test starts from. */
    public static ManagedVm stoppedUtm(LocalVm vm) {
        return new ManagedVm(new VmId("utm", vm.id()), "UTM", vm, VmStatus.STOPPED);
    }
}
