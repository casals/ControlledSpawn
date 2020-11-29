# ControlledSpawn 

Built over MR night spawning system

Usage:

- Add a `ControlledSpawnComponent` to a prefab
- Set the `period` parameter to either "DAY" or "NIGHT"
- All prefabs with the component above should be randomly spawned according to their period

This implementation is beyond dirty, but it illustrates parametrized spawning with `org.terasology.behaviors.system.NightTrackerSystem`

## TODO:

- Structure combinations according to `org.terasology.world.sun` events
- Reuse periodic spawning structure from the `Spawning` module
- Implement a generic period-to-group mechanism so different behaviors can be associated with different spawning periods 
- `period` should be a list, so we can have different behaviors applied to the same prefab
