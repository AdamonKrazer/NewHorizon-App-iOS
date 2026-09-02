# New Horizon session flow

Source snapshot for the bundled Forge 1.20.1 client mod stored in
`Natives/resources/newhorizon/bundled-mods`.

The local test path is activated with `-Dnewhorizon.localWorldTest=true`.
It opens the stable save `New_Horizon_GPU_Test` when it already exists. On its
first run it creates the save with Minecraft's built-in `flat` world preset,
Creative mode, Peaceful difficulty, commands enabled, structures disabled and a
fixed seed. This mode only changes session routing; rendering remains on the
GPU-only LTW path selected by the native launcher.

The checked-in JARs are built against the Forge 1.20.1 SRG client artifacts.
When this source changes, both bundled variants must receive the newly compiled
session-flow classes; the low-memory variant keeps its separately optimized
memory-governor classes.
