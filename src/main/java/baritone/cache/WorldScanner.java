/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.cache;

import baritone.api.cache.ICachedWorld;
import baritone.api.cache.IWorldScanner;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.IPlayerContext;
import java.util.*;
import java.util.stream.IntStream;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

public enum WorldScanner implements IWorldScanner {

    INSTANCE;

    @Override
    public List<BlockPos> scanChunkRadius(IPlayerContext ctx, BlockOptionalMetaLookup filter, int maxPerType, int yLevelThreshold, int maxSearchRadius) {
        ArrayList<BlockPos> res = new ArrayList<>();

        if (filter.blocks().isEmpty()) {
            return res;
        }
        ClientChunkCache chunkProvider = (ClientChunkCache) ctx.world().getChunkSource();

        int maxSearchRadiusSq = maxSearchRadius * maxSearchRadius;
        int playerChunkX = ctx.playerFeet().getX() >> 4;
        int playerChunkZ = ctx.playerFeet().getZ() >> 4;
        int playerY = ctx.playerFeet().getY() - ctx.world().dimensionType().minY();

        int playerYBlockStateContainerIndex = playerY >> 4;
        int[] coordinateIterationOrder = IntStream.range(0, ctx.world().dimensionType().height() / 16).boxed().sorted(Comparator.comparingInt(y -> Math.abs(y - playerYBlockStateContainerIndex))).mapToInt(x -> x).toArray();

        // How many positions we can track per block
        HashMap<Block, Integer> countByType = new HashMap<>();
        HashSet<Block> toFindAtYLevel = new HashSet<>();
        for (BlockOptionalMeta bom : filter.blocks()) {
            countByType.put(bom.getBlock(), maxPerType);
            toFindAtYLevel.add(bom.getBlock());
        }


        int searchRadiusSq = 0;
        boolean foundWithinY = false;
        while (true) {
            boolean allUnloaded = true;
            boolean foundChunks = false;
            for (int xoff = -searchRadiusSq; xoff <= searchRadiusSq; xoff++) {
                for (int zoff = -searchRadiusSq; zoff <= searchRadiusSq; zoff++) {
                    // We have completely filled all of our blocks.
                    if (countByType.isEmpty()) {
                        return res;
                    }
                    int distance = xoff * xoff + zoff * zoff;
                    if (distance != searchRadiusSq) {
                        continue;
                    }
                    foundChunks = true;
                    int chunkX = xoff + playerChunkX;
                    int chunkZ = zoff + playerChunkZ;
                    LevelChunk chunk = chunkProvider.getChunk(chunkX, chunkZ, null, false);
                    if (chunk == null) {
                        continue;
                    }
                    allUnloaded = false;
                    if (scanChunkInto(chunkX << 4, chunkZ << 4, ctx.world().dimensionType().minY(), chunk, filter, res, countByType, toFindAtYLevel, yLevelThreshold, playerY, coordinateIterationOrder)) {
                        foundWithinY = true;
                    }
                }
            }
            if ((allUnloaded && foundChunks)
                    || (searchRadiusSq > maxSearchRadiusSq || (searchRadiusSq > 1 && foundWithinY))
            ) {
                return res;
            }
            searchRadiusSq++;
        }
    }

    @Override
    public List<BlockPos> scanChunk(IPlayerContext ctx, BlockOptionalMetaLookup filter, ChunkPos pos, int max, int yLevelThreshold) {
        if (filter.blocks().isEmpty()) {
            return Collections.emptyList();
        }

        ClientChunkCache chunkProvider = (ClientChunkCache) ctx.world().getChunkSource();
        LevelChunk chunk = chunkProvider.getChunk(pos.x, pos.z, null, false);
        int playerY = ctx.playerFeet().getY();

        if (chunk == null || chunk.isEmpty()) {
            return Collections.emptyList();
        }

        // How many positions we can track per block
        HashMap<Block, Integer> countByType = new HashMap<>();
        HashSet<Block> toFindAtYLevel = new HashSet<>();
        for (BlockOptionalMeta bom : filter.blocks()) {
            countByType.put(bom.getBlock(), max / filter.blocks().size());
            toFindAtYLevel.add(bom.getBlock());
        }

        ArrayList<BlockPos> res = new ArrayList<>();
        scanChunkInto(pos.x << 4, pos.z << 4, ctx.world().dimensionType().minY(), chunk, filter, res, countByType, toFindAtYLevel, yLevelThreshold, playerY, IntStream.range(0, ctx.world().dimensionType().height() / 16).toArray());
        return res;
    }

    @Override
    public int repack(IPlayerContext ctx) {
        return this.repack(ctx, 40);
    }

    @Override
    public int repack(IPlayerContext ctx, int range) {
        ChunkSource chunkProvider = ctx.world().getChunkSource();
        ICachedWorld cachedWorld = ctx.worldData().getCachedWorld();

        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - range;
        int minZ = playerChunkZ - range;
        int maxX = playerChunkX + range;
        int maxZ = playerChunkZ + range;

        int queued = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    queued++;
                    cachedWorld.queueForPacking(chunk);
                }
            }
        }

        return queued;
    }

    private boolean scanChunkInto(int chunkX, int chunkZ, int minY, LevelChunk chunk, BlockOptionalMetaLookup filter, Collection<BlockPos> result, HashMap<Block, Integer> countRemainingByType, HashSet<Block> toFindWithinY, int yLevelThreshold, int playerY, int[] coordinateIterationOrder) {
        LevelChunkSection[] chunkInternalStorageArray = chunk.getSections();
        boolean foundWithinY = false;
        for (int y0 : coordinateIterationOrder) {
            LevelChunkSection section = chunkInternalStorageArray[y0];
            if (section == null || LevelChunkSection.isEmpty(section)) {
                continue;
            }
            int yReal = y0 << 4;
            PalettedContainer<BlockState> bsc = section.getStates();
            for (int yy = 0; yy < 16; yy++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        // We've filled ALL our blocks, maximally
                        if (countRemainingByType.isEmpty()) {
                            return foundWithinY;
                        }
                        BlockState state = bsc.get(x, yy, z);
                        Block b = state.getBlock();
                        if (filter.has(state) && countRemainingByType.containsKey(state.getBlock())) {
                            int y = yReal | yy;
                            if (Math.abs(y - playerY) < yLevelThreshold) {
                                // This block is found within y, keep searching.
                                toFindWithinY.remove(b);
                                if (toFindWithinY.isEmpty()) {
                                    foundWithinY = true;
                                }
                            } else {
                                if (foundWithinY) {
                                    // have found within Y in this chunk, so don't need to consider outside Y
                                    // TODO continue iteration to one more sorted Y coordinate block
                                    return true;
                                }
                            }
                            result.add(new BlockPos(chunkX | x, y + minY, chunkZ | z));
                            // Update count remaining
                            countRemainingByType.put(b, countRemainingByType.get(b) - 1);
                            if (countRemainingByType.get(b) <= 0) {
                                countRemainingByType.remove(b);
                            }
                        }
                    }
                }
            }
        }
        return foundWithinY;
    }
}
