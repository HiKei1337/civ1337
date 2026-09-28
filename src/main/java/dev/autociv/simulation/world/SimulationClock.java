package dev.autociv.simulation.world;

/** Lightweight server-tick accumulator that signals when one abstract day is due. */
public final class SimulationClock {

    private int elapsedTicks;

    public boolean advance(SimulationSpeed speed) {
        elapsedTicks++;
        if (elapsedTicks < speed.intervalTicks()) {
            return false;
        }
        elapsedTicks = 0;
        return true;
    }

    public void reset() {
        elapsedTicks = 0;
    }
}