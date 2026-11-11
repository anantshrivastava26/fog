package carbonfog.model;

import carbonfog.energy.Region;

/**
 * Hardware/energy description of a compute node.
 *
 * @param idleW / maxW  IT power at 0% and 100% CPU (iFogSim FogLinearPowerModel)
 * @param pue           facility overhead multiplier (cooling, UPS); for devices: charging loss
 * @param solarKWp      on-site rooftop PV capacity feeding this node (0 = grid only)
 */
public record NodeSpec(String name, String label, Tier tier, int zone, int cores, double mipsPerCore,
                       double idleW, double maxW, double pue, double solarKWp, Region region) {
}
