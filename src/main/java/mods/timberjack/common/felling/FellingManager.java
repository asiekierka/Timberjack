/*
 * Copyright (c) CovertJaguar, 2011-2017
 *
 * This work (Timberjack) is licensed under the "MIT" License,
 * see LICENSE in root folder for details.
 */

package mods.timberjack.common.felling;

import com.google.common.collect.MapMaker;
import mods.timberjack.common.TimberjackConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by CovertJaguar on 4/15/2017 for Railcraft.
 *
 * @author CovertJaguar <http://www.railcraft.info>
 */
public class FellingManager {
    public static final Map<World, FellingManager> fellingManagers = new MapMaker().weakKeys().makeMap();
    private final Collection<Tree> fellQueue = new LinkedList<>();
    private final World world;

    FellingManager(World world) {
        this.world = world;
    }

    public boolean isEmpty() {
        return fellQueue.isEmpty();
    }

    public int treesQueuedToFell() {
        return fellQueue.size();
    }

    public int logsQueuedToFell() {
        int logs = 0;
        for (FellingManager.Tree tree : fellQueue) {
            logs += tree.logsQueuedToFell();
        }
        return logs;
    }

    void tick() {
//            long worldTime = event.world.getTotalWorldTime();
//            if (worldTime % 4 != 0)
//                return;
//        if(!fellQueue.isEmpty())
//            System.out.printf("Felling %d trees\n", fellQueue.size());
        fellQueue.removeIf(tree -> !tree.hasLogsToFell());
        fellQueue.forEach(tree -> {
            tree.prepForFelling();
            Iterator<BlockPos> it = tree.logsToFell.iterator();
            if (it.hasNext()) {
                BlockPos log = it.next();
                tree.fellLog(log);
                it.remove();
            }
        });
    }

    void onChop(BlockPos pos, EnumFacing fellingDirection) {
        Tree tree = new Tree(pos, fellingDirection);
        tree.buildTree();
        tree.queueForFelling();
    }

    private class Tree {
        private Collection<Branch> branches = new ConcurrentLinkedQueue<>();
        private Set<BlockPos> logs = new HashSet<>();
        private List<BlockPos> logsToFell = new LinkedList<>();
        private List<BlockPos> newLogsToFell = new LinkedList<>();
        private final BlockPos choppedBlock;
        private Vec3d centroid = Vec3d.ZERO;
        private boolean isTreehouse;
        private final EnumFacing fellingDirection;

        Tree(BlockPos choppedBlock, EnumFacing fellingDirection) {
            this.choppedBlock = choppedBlock;
            this.fellingDirection = fellingDirection;
            makeBranch(choppedBlock);
        }

        boolean contains(BlockPos pos) {
            return logs.contains(pos);
        }

        int size() {
            return logs.size();
        }

        int logsQueuedToFell() {
            return logsToFell.size() + newLogsToFell.size();
        }

        void addLogsToFell(Collection<BlockPos> logs) {
            newLogsToFell.addAll(logs);
        }

        void prepForFelling() {
            if (!newLogsToFell.isEmpty()) {
                logsToFell.addAll(newLogsToFell);
                updateCentroid();
                logsToFell.sort((o1, o2) -> {
                    int yCompare = Integer.compare(o1.getY(), o2.getY());
                    if (yCompare != 0)
                        return yCompare;
                    int distCompare = Double.compare(centroid.squareDistanceTo(o2.getX(), o2.getY(), o2.getZ()),
                            centroid.squareDistanceTo(o1.getX(), o1.getY(), o1.getZ()));
                    if (distCompare != 0)
                        return distCompare;
                    return o1.compareTo(o2);
                });
                newLogsToFell.clear();
            }
        }

        void updateCentroid() {
            double x = 0;
            double y = 0;
            double z = 0;
            for (BlockPos pos : logs) {
                x += pos.getX();
                y += pos.getY();
                z += pos.getZ();
            }
            int size = logs.size();
            x /= size;
            y /= size;
            z /= size;
            centroid = new Vec3d(x, y, z);
        }

        boolean hasLogsToFell() {
            return !isTreehouse && (!logsToFell.isEmpty() || !newLogsToFell.isEmpty());
        }

        Branch makeBranch(BlockPos pos) {
            Branch branch = new Branch(this, pos);
            branches.add(branch);
            return branch;
        }

        private void buildTree() {
            TimberjackUtils.iterateBlocks(1, choppedBlock, targetPos -> {
                if (!contains(targetPos)) {
                    IBlockState targetState = world.getBlockState(targetPos);
                    if (TimberjackUtils.isWood(targetState, world, targetPos)) {
                        scanNewBranch(targetPos.toImmutable());
                    }
                }
            });
        }

        private void scanNewBranch(BlockPos pos) {
            Branch branch = makeBranch(pos);
            branch.scan();
        }

        private void queueForFelling() {
            if (hasLogsToFell())
                fellQueue.add(this);
        }

        private void fellLog(BlockPos logPos) {
            TimberjackUtils.spawnFallingLog(world, logPos, centroid, fellingDirection);
            TimberjackUtils.iterateBlocks(4, logPos, targetPos -> {
                IBlockState targetState = world.getBlockState(targetPos);
                if (TimberjackUtils.isLeaves(targetState, world, targetPos)) {
                    TimberjackUtils.spawnFallingLeaves(world, targetPos, logPos, centroid, targetState, fellingDirection);
                } else if (TimberjackUtils.isWood(targetState, world, targetPos) && !contains(targetPos)) {
                    scanNewBranch(targetPos.toImmutable());
                }
            });
        }
    }

    private class Branch {
        private Set<BlockPos> logs = new HashSet<>();
        private final Tree tree;
        private final BlockPos start;
        private boolean hasLeaves;
        private boolean rooted;

        Branch(Tree tree, BlockPos start) {
            this.tree = tree;
            this.start = start;
            addLog(new BlockPos.MutableBlockPos(start));
        }

        private void scan() {
            expandLogs(start);
            if (hasLeaves && !rooted && (tree.size() < TimberjackConfig.getMaxLogsProcessed() || TimberjackConfig.canFellLargeTrees()))
                tree.addLogsToFell(logs);
        }

        private BlockPos addLog(BlockPos.MutableBlockPos targetPos) {
            BlockPos immutable = targetPos.toImmutable();
            logs.add(immutable);
            tree.logs.add(immutable);
            if (!rooted) {
                targetPos.move(EnumFacing.DOWN);
                if (!tree.contains(targetPos)) {
                    IBlockState targetState = world.getBlockState(targetPos);
                    if (TimberjackUtils.isDirt(targetState, world, targetPos)) {
                        rooted = true;
                    }
                }
            }
            return immutable;
        }

        private void expandLogs(BlockPos root) {
            if (tree.size() >= TimberjackConfig.getMaxLogsProcessed())
                return;

            Deque<BlockPos> logsToExpand = new ArrayDeque<>();
            logsToExpand.add(root);
            BlockPos nextBlock;
            while ((nextBlock = logsToExpand.poll()) != null && !tree.isTreehouse) {
                TimberjackUtils.iterateBlocks(1, nextBlock, targetPos -> {
                    if (!tree.contains(targetPos)) {
                        IBlockState targetState = world.getBlockState(targetPos);
                        if (TimberjackUtils.isWood(targetState, world, targetPos)) {
                            if (tree.size() < TimberjackConfig.getMaxLogsProcessed()) {
                                logsToExpand.addLast(addLog(targetPos));
                            }
                        } else if (!hasLeaves && TimberjackUtils.isLeaves(targetState, world, targetPos)) {
                            hasLeaves = true;
                        } else if (TimberjackUtils.isHouse(targetState, world, targetPos)) {
                            tree.isTreehouse = true;
                        }
                    }
                });
            }
        }
    }

}
