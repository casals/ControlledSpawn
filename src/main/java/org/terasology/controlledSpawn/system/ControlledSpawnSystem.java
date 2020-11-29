// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.controlledSpawn.system;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.behaviors.system.NightTrackerSystem;
import org.terasology.controlledSpawn.component.ControlledSpawnComponent;
import org.terasology.entitySystem.entity.EntityBuilder;
import org.terasology.entitySystem.entity.EntityManager;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.prefab.Prefab;
import org.terasology.entitySystem.prefab.PrefabManager;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.logic.location.LocationComponent;
import org.terasology.math.JomlUtil;
import org.terasology.registry.CoreRegistry;
import org.terasology.registry.In;
import org.terasology.utilities.random.FastRandom;
import org.terasology.utilities.random.Random;
import org.terasology.world.WorldProvider;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockManager;
import org.terasology.world.chunks.ChunkConstants;
import org.terasology.world.chunks.event.BeforeChunkUnload;
import org.terasology.world.chunks.event.OnChunkLoaded;
import org.terasology.world.sun.OnDawnEvent;
import org.terasology.world.sun.OnDuskEvent;

import java.util.*;

/**
 * Spawns enemies outside the area of settlements at nighttime. These enemies are destroyed if they go inside a city,
 * or the sun rises.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class ControlledSpawnSystem extends BaseComponentSystem implements UpdateSubscriberSystem {

    private final Logger logger = LoggerFactory.getLogger(ControlledSpawnSystem.class);

    @In
    private EntityManager entityManager;

    @In
    private PrefabManager prefabManager;

    @In
    private WorldProvider worldProvider;

    @In
    private BlockManager blockManager;

    @In
    private NightTrackerSystem nightTrackerSystem;

    /**
     * The amount of time elapsed since the last processed update (spawned/destroyed enemies).
     */
    private float elapsedTime;

    /**
     * The time period between processing updates in seconds.
     */
    private static final float UPDATE_PERIOD = 1;

    /**
     * Contains all enemies currently in the world. Used to remove enemies in spawn order when the maximum number of
     * enemies is reached.
     */
    private Queue<EntityRef> enemyQueue;

    private List<Prefab> dayEnemies;
    private List<Prefab> nightEnemies;

    //TODO: structure combinations according to org.terasology.world.sun events
    //TODO: Check periodic spawning structure from the Spawning module
    private static final String DAY_SPAWN = "DAY";
    private static final String NIGHT_SPAWN = "NIGHT";

    /**
     * A list of positions of loaded chunks that enemies can be spawned in
     */
    private List<Vector3i> chunkPositions;

    /**
     * The maximum number of enemies that can spawn in the world.
     */
    private static final int MAX_ENEMIES = 30;

    /**
     * True if this system is initialised, false otherwise.
     */
    private boolean ready;

    /**
     * A random value generator used to determine enemy spawn positions.
     */
    private Random random;

    @Override
    public void postBegin() {
        nightTrackerSystem = CoreRegistry.get(NightTrackerSystem.class);
        random = new FastRandom();

        enemyQueue = Queues.newLinkedBlockingQueue();
        chunkPositions = Lists.newArrayList();

        //Get entities with controlled spawning rules
        dayEnemies = new ArrayList<>();
        nightEnemies = new ArrayList<>();

        Collection<Prefab> spawnablePrefabs = prefabManager.listPrefabs(ControlledSpawnComponent.class);
        logger.info("Grabbed all controlled entities - got: {}", spawnablePrefabs);

        for (Prefab prefab : spawnablePrefabs) {
            logger.info("Triaging prefab: {}", prefab);
            ControlledSpawnComponent spawnableComponent = prefab.getComponent(ControlledSpawnComponent.class);
            String spawningPeriod = spawnableComponent.period;
            if(!Strings.isNullOrEmpty(spawningPeriod)) {
                if(spawningPeriod.trim().toUpperCase().equals(DAY_SPAWN))
                    dayEnemies.add(prefab);

                else if(spawningPeriod.trim().toUpperCase().equals(NIGHT_SPAWN))
                    nightEnemies.add(prefab);

            }

        }

        ready = true;
    }

    @Override
    public void update(float delta) {
        // Don't spawn enemies before system is ready
        if (!ready) {
            return;
        }

        // TODO: Consider using `DelayManager`'s periodic actions
        elapsedTime += delta;
        if (elapsedTime < UPDATE_PERIOD) {
            return;
        }
        elapsedTime -= UPDATE_PERIOD;

        if (enemyQueue.size() < MAX_ENEMIES) {
            spawnEnemyInWorld();
        }

        // Removes enemies that have entered a settlement
        enemyQueue.removeIf(enemy -> {
            // prevents a rare NPE where an enemy has no location component
            if (!enemy.hasComponent(LocationComponent.class)) {
                removeEnemy(enemy);
                logger.warn("Removed enemy without a location component");
                return true;
            }

            LocationComponent locComp = enemy.getComponent(LocationComponent.class);
            Vector3f enemyLoc = locComp.getWorldPosition(new Vector3f());

            if (enemy.isActive()) {
                return false;
            }
            removeEnemy(enemy);
            logger.debug("Removed inactive enemy at ({}, {}, {}).", enemyLoc.x(), enemyLoc.y(), enemyLoc.z());
            return true;
        });
    }

    @ReceiveEvent
    public void onDawnEvent(OnDawnEvent event, EntityRef entityRef) {
        logger.debug("Dawn event invoked, despawning all enemies.");
        while (!enemyQueue.isEmpty()) {
            removeEnemy(enemyQueue.remove());
        }
    }

    @ReceiveEvent
    public void onDuskEvent(OnDuskEvent event, EntityRef entityRef) {
        logger.debug("Dusk event invoked, despawning all enemies.");
        while (!enemyQueue.isEmpty()) {
            removeEnemy(enemyQueue.remove());
        }
    }

    @ReceiveEvent
    public void chunkLoadedEvent(OnChunkLoaded event, EntityRef entity) {
        chunkPositions.add(JomlUtil.from(event.getChunkPos()));
    }

    @ReceiveEvent
    public void beforeChunkUnload(BeforeChunkUnload event, EntityRef entity) {
        chunkPositions.remove(event.getChunkPos());
    }

    /**
     * Attempts to spawn an enemy in the game world outside of cities in loaded chunks.
     */
    private void spawnEnemyInWorld() {
        Vector3i spawnPosition = findSpawnPosition();

        if (spawnPosition != null) {
            spawnOnPosition(spawnPosition);
        }
    }

    /**
     * Finds a random coordinate position that can be used to spawn enemies.
     *
     * @return A randomly chosen possible spawn position, or null if none could be found.
     */
    private Vector3i findSpawnPosition() {
        if (chunkPositions.isEmpty()) {
            logger.debug("No currently spawned chunks, skipping spawn cycle....");
            return null;
        }

        Vector3i chunkPosition = chunkPositions.get(random.nextInt(chunkPositions.size()));
        Vector3i chunkWorldPosition = chunkPosition.mul(ChunkConstants.SIZE_X, ChunkConstants.SIZE_Y, ChunkConstants.SIZE_Z);
        Vector2i randomColumn = new Vector2i(chunkWorldPosition.x + random.nextInt(ChunkConstants.SIZE_X),
                chunkWorldPosition.z + random.nextInt(ChunkConstants.SIZE_Z));

        if (!worldProvider.isBlockRelevant(chunkWorldPosition)) {
            // 2nd line of defense in case chunk load/unload events are skipped.
            chunkPositions.remove(chunkPosition);
            logger.warn("Inactive chunk requested! Removing chunk from spawn list and skipping spawn cycle");
            return null;
        }

        for (int y = chunkWorldPosition.y - ChunkConstants.SIZE_Y; y < chunkWorldPosition.y + ChunkConstants.SIZE_Y; y++) {
            Vector3i possiblePosition = new Vector3i(randomColumn.x, y, randomColumn.y);
            if (isValidSpawnPosition(possiblePosition)) {
                return possiblePosition;
            }
        }

        logger.debug("No valid position found in column ({}, {}) inside chunk ({}, {}, {}), skipping spawn cycle.",
                randomColumn.x, randomColumn.y, chunkPosition.x, chunkPosition.y, chunkPosition.z);
        return null;
    }

    /**
     * Returns true if the given position is a valid position to spawn an enemy character.
     *
     * @param pos The position to check.
     * @return If the position is valid or not.
     */
    private boolean isValidSpawnPosition(Vector3i pos) {

        Vector3i below = new Vector3i(pos.x, pos.y - 1, pos.z);
        Block blockBelow = worldProvider.getBlock(below);
        if (blockBelow.isPenetrable()) {
            return false;
        }

        Block blockAtPosition = worldProvider.getBlock(pos);
        if (!blockAtPosition.isPenetrable()) {
            return false;
        }

        Vector3i above = new Vector3i(pos.x, pos.y + 1, pos.z);
        Block blockAbove = worldProvider.getBlock(above);
        if (!blockAbove.isPenetrable()) {
            return false;
        }

        return true;
    }

    /**
     * Spawns an enemy on the given world coordinate.
     *
     * @param pos The position to spawn a character on top of.
     */
    private void spawnOnPosition(Vector3i pos) {

        List<Prefab> availablePrefabs;
        if(nightTrackerSystem.isNight())
            availablePrefabs = nightEnemies;
        else
            availablePrefabs = dayEnemies;

        //using a separate random, just in case
        Random rand = new FastRandom();
        int randomIndex = rand.nextInt(availablePrefabs.size());
        Prefab luckyPrefab = availablePrefabs.get(randomIndex);

        EntityBuilder entityBuilder = entityManager.newBuilder(luckyPrefab);
        LocationComponent locationComponent = entityBuilder.getComponent(LocationComponent.class);

        locationComponent.setWorldPosition(new Vector3f(pos));
        entityBuilder.saveComponent(locationComponent);

        EntityRef enemyEntity = entityBuilder.build();
        enemyQueue.add(enemyEntity);
    }

    /**
     * Destroys the character entity.
     *
     * @param enemy The enemy to remove.
     */
    private void removeEnemy(EntityRef enemy) {
        enemy.destroy();
    }

}
