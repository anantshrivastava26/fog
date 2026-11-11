package carbonfog.energy;

/** Electricity grid a node draws from. */
public enum Region {
    /** Local campus grid: coal-heavy, evening peak (Indian-grid-like, ~560-780 gCO2/kWh). */
    CAMPUS_GRID,
    /** Remote cloud region with a large renewable share (~70-240 gCO2/kWh). */
    CLOUD_REGION
}
