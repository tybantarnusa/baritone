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

package baritone.process;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.*;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.schematica.SchematicaHelper;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {
    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    private Map<BlockState, Integer> missing;
    private boolean active;
    private Stack<Object> stateStack = new Stack<>();
    private Vec3i schemSize;
    private boolean fromAltoclefFinished;
    private boolean fromAltoclef;
    private BlockPos aboveBreak = null;
    private final List<BlockPos> ignoredBlocksInMesh = new ArrayList<>();
    private long systemMillisOfPrevRecalc = 0;
    private long recalcCounter = 0;
    private boolean pausedBecauseOfMissingMaterials = false;
    private final Map<BlockState, Integer> protectedItems = new HashMap<>();
    private Snake snake;

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        //Shouldn't get initially called
        if (this.fromAltoclef && this.stateStack.isEmpty()) {
            //System.out.println("BARITONE: PUSHED FROM BUILD");
            pushState();
            //System.out.println("BARITONE: PUSHED");
        }

        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.active = true;

        //stopProtectItemOfMissing();

        if (this.missing != null) {
            this.missing.clear();
        } else {
            missing = new HashMap<>();
        }

        this.schemSize = new Vec3i(schematic.widthX(), schematic.heightY(), schematic.lengthZ());
        this.fromAltoclefFinished = false;
        this.fromAltoclef = false;
    }

    private void protectItemOfMissing() {
        if (missing != null && missing.keySet() != null) {
            protectedItems.putAll(missing);
            protectedItems.keySet().forEach(e -> {
                if (!Baritone.getAltoClefSettings().isItemProtected(e.getBlock().asItem())) {
                    //System.out.println(Baritone.getAltoClefSettings().getProtectedItems().size());
                    //System.out.println("SET PROTECTION ");
                    Baritone.getAltoClefSettings().protectItem(e.getBlock().asItem());
                    //System.out.println(Baritone.getAltoClefSettings().getProtectedItems().size());
                }
            });

            /*
            missing.keySet().forEach(e -> {
                if (!Baritone.getAltoClefSettings().isItemProtected(e.getBlock().asItem())) {
                    System.out.println(Baritone.getAltoClefSettings().getProtectedItems().size());
                    System.out.println("SET PROTECTION ");
                    Baritone.getAltoClefSettings().protectItem(e.getBlock().asItem());
                    System.out.println(Baritone.getAltoClefSettings().getProtectedItems().size());

                }
            });*/
        }
    }

    private void stopProtectItemOfMissing() {
        if (protectedItems != null && protectedItems.keySet() != null && protectedItems.size() > 0) {
            protectedItems.keySet().forEach(e -> {
                if (Baritone.getAltoClefSettings().isItemProtected(e.getBlock().asItem())) {
                    Baritone.getAltoClefSettings().stopProtectingItem(e.getBlock().asItem());
                }
            });
        }
        protectedItems.clear();

        /*if (missing != null && missing.keySet() != null) {
            missing.keySet().forEach(e -> {
                if (Baritone.getAltoClefSettings().isItemProtected(e.getBlock().asItem())) {
                    Baritone.getAltoClefSettings().stopProtectingItem(e.getBlock().asItem());
                }
            });
        }*/
    }

    public boolean isPausedBecauseOfMissingMaterials() {
        return this.pausedBecauseOfMissingMaterials;
    }

    @Override
    public Vec3i getSchemSize() {
        return schemSize;
    }

    private void pushState() {
        stateStack.clear();
        stateStack.push(this.approxPlaceable);
        stateStack.push(this.ticks);
        stateStack.push(this.incorrectPositions);
        stateStack.push(this.name);
        stateStack.push(this.schematic);
        stateStack.push(this.realSchematic);
        stateStack.push(this.origin);
        stateStack.push(this.paused);
        stateStack.push(this.layer);
        stateStack.push(this.numRepeats);
        stateStack.push(this.observedCompleted);
        stateStack.push(this.active);
        stateStack.push(this.missing);
        stateStack.push(this.schemSize);
        stateStack.push(this.fromAltoclefFinished);
        stateStack.push(this.fromAltoclef);
        stateStack.push(this.pausedBecauseOfMissingMaterials);
        //stateStack.push(this.protectedItems);
    }

    private void popStack() {
        //this.protectedItems = (Map<BlockState, Integer>) stateStack.pop();
        this.pausedBecauseOfMissingMaterials = (boolean) stateStack.pop();
        this.fromAltoclef = (boolean) stateStack.pop();
        this.fromAltoclefFinished = (boolean) stateStack.pop();
        this.schemSize = (Vec3i) stateStack.pop();
        //stopProtectItemOfMissing();
        this.missing = (Map<BlockState, Integer>) stateStack.pop();
        //protectItemOfMissing();
        this.active = (boolean) stateStack.pop();
        this.observedCompleted = (LongOpenHashSet) stateStack.pop();
        this.numRepeats = (int) stateStack.pop();
        this.layer = (int) stateStack.pop();
        this.paused = (boolean) stateStack.pop();
        this.origin = (Vec3i) stateStack.pop();
        this.realSchematic = (ISchematic) stateStack.pop();
        this.schematic = (ISchematic) stateStack.pop();
        //System.out.println("TYPE: " + schematic.widthX());
        this.name = (String) stateStack.pop();
        this.incorrectPositions = (HashSet<BetterBlockPos>) stateStack.pop();
        this.ticks = (int) stateStack.pop();
        this.approxPlaceable = (List<BlockState>) stateStack.pop();

        pushState();

        if (!stateStack.isEmpty()) {
            logDebug("ERROR: state stack was not empty after state restoration. Will throw away the rest for now.");
            stateStack.clear();
        }
    }

    @Override
    public boolean clearState() {
        final boolean isEmpty = !stateStack.isEmpty();
        stateStack.clear();
        return isEmpty;
    }

    @Override
    public boolean isFromAltoclefFinished() {
        return this.fromAltoclefFinished;
    }

    public void resume() {
        if (!this.stateStack.isEmpty()) {
            popStack();
            //System.out.println("BARITONE: POPED FROM BUILD");
        }

        this.paused = false;
        this.active = true;
    }

    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean isFromAltoclef() {
        return this.fromAltoclef;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }

        ISchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        if (Baritone.settings().mapArtMode.value) {
            parsed = new MapArtSchematic((IStaticSchematic) parsed);
        }

        build(name, parsed, origin);
        return true;
    }

     @Override
    public void build(String name, ISchematic schematic, Vec3i origin, boolean fromAltoclef) {
        this.build(name, schematic, origin);
         this.fromAltoclef = fromAltoclef;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin, boolean fromAltoclef) {
        final boolean bl = this.build(name, schematic, origin);
        this.fromAltoclef = fromAltoclef;
        return bl;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic s = schematic.get().getA();
                this.build(
                        schematic.get().getA().toString(),
                        Baritone.settings().mapArtMode.value ? new MapArtSchematic(s) : s,
                        schematic.get().getB()
                );
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public void reset() {
        onLostControl();
    }

    @Override
    public Map<BlockState, Integer> getMissing() {
        return (this.fromAltoclef) ? new HashMap<>(this.missing) : null;
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return this.approxPlaceable;
    }

    @Override
    public boolean isActive() {
        return active;//schematic != null && !paused;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (this.schematic == null) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }

        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    private boolean blockPosMatches(final BlockPos pos1, final BlockPos pos2) {
        return (pos1.getX() == pos2.getX() && pos1.getY() == pos2.getY() && pos1.getZ() == pos2.getZ());
    }

    @Override
    public void addBlockToIgnoreInSchematic(final BlockPos pos) {
        this.ignoredBlocksInMesh.add(pos);
    }

    @Override
    public void removeBlockToIgnoreInSchematic(final BlockPos pos) {
        this.ignoredBlocksInMesh.removeIf(e -> blockPosMatches(e, pos));
    }

    @Override
    public void clearBlocksToIgnoreInSchematic() {
        this.ignoredBlocksInMesh.clear();
    }

    private boolean isBlacklistedByAltoclef(final BlockPos pos) {
        return this.ignoredBlocksInMesh.stream().anyMatch(e -> blockPosMatches(e, pos));
    }

    public void decideToIgnoreInSchematic(BetterBlockPos positionToPlace) {
        /*if (isActive() && isFromAltoclef()) {
            // I was too lazy to check which one should be checked
            if ((this.realSchematic != null && realSchematic.inSchematic(positionToPlace.x, positionToPlace.y, positionToPlace.z, ctx.world().getBlockState(positionToPlace)))
                    || (this.schematic != null && schematic.inSchematic(positionToPlace.x, positionToPlace.y, positionToPlace.z, ctx.world().getBlockState(positionToPlace)))) {
                BlockState state = ctx.world().getBlockState(positionToPlace);
                if (state.getBlock() instanceof AirBlock) {
                    System.out.println("where banana?");
                    this.ignoredBlocksInMesh.add(positionToPlace);
                }
            }
        }*/
    }

    private Optional<Tuple<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }

                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }

                    /*
                    if (isBlacklistedByAltoclef(new BlockPos(x, y, z))) {
                        System.out.println("f");
                        continue;
                    }*/
                    final BlockPos tmp = new BlockPos(x, y, z);
                    if (anyHistoryMatch(tmp) && breakLimitExceeded(tmp)) {
                        continue;
                    }

                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !valid(curr, desired, false)) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        Optional<Rotation> rot = RotationUtils.reachable(ctx.player(), pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            return Optional.of(new Tuple<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
        }
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
                            continue;
                        }
                        desirableOnHotbar.add(desired);
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            return opt;
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (placeAgainstState.getBlock() instanceof AirBlock) {
                // isReplacable used to check for this, but we changed that.
                continue;
            }
            if (!toPlace.canSurvive(ctx.world(), new BetterBlockPos(x, y, z))) {
                continue;
            }
            if (!placementPlausible(new BetterBlockPos(x, y, z), toPlace)) {
                continue;
            }
            if (ignoredBlocksInMesh.stream().anyMatch(e -> blockPosMatches(e, new BlockPos(x, y, z)))) {
                continue;
            }
            AABB aabb = placeAgainstState.getShape(ctx.world(), placeAgainstPos).bounds();
            for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
                double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
                double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
                double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
                Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot, ctx.playerController().getBlockReachDistance(), true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == against.getOpposite()) {
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, rot);
                    if (hotbar.isPresent()) {
                        return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            float originalYaw = ctx.player().getYRot();
            float originalPitch = ctx.player().getXRot();
            // the state depends on the facing of the player sometimes
            ctx.player().setYRot(rot.getYaw());
            ctx.player().setXRot(rot.getPitch());
            BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(
                    ctx.world(),
                    ctx.player(),
                    InteractionHand.MAIN_HAND,
                    stack,
                    (BlockHitResult) result
            ) {}); // that {} gives us access to a protected constructor lmfao
            BlockState wouldBePlaced = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(meme);
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            if (wouldBePlaced == null) {
                continue;
            }
            if (!meme.canPlace()) {
                continue;
            }
            if (valid(wouldBePlaced, desired, true)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException();
        }
    }

    private final class HistoryInfo {
        public long counter = 0;
        public boolean brokenPreviously = false;
    }

    private Map<BlockPos, HistoryInfo> blockBreakHistory = new HashMap<>();

    private BlockPos getFromHistory(final BlockPos pos) {
        if (pos == null) return null;

        final Optional<BlockPos> opt = blockBreakHistory.keySet().stream()
                .filter(e -> e.getX() == pos.getX() && e.getY() == pos.getY() && e.getZ() == pos.getZ()).findFirst();

        if (opt.isPresent()) {
            return opt.get();
        }

        return null;
    }

    private boolean anyHistoryMatch(final BlockPos pos) {
        return getFromHistory(pos) != null; //blockBreakHistory.keySet().stream().anyMatch(e -> e.getX() == pos.getX() && e.getY() == pos.getY() && e.getZ() == pos.getZ());
    }

    private boolean breakLimitExceeded(final BlockPos pos) {
        if(!anyHistoryMatch(pos)) {
            return false;
        }

        return blockBreakHistory.get(getFromHistory(pos)).counter > 10;
    }

    private void noteRemoval(final BlockPos pos) {
        if (pos == null) return;

        if (anyHistoryMatch(pos)) {
            final BlockPos orig = getFromHistory(pos);
            final HistoryInfo info = blockBreakHistory.get(orig);
            if (!info.brokenPreviously) {
                info.brokenPreviously = true;
            }
            //System.out.println("remove " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " c: " + getHistoryCount(pos));
        }

    }

    @Override
    public void noteInsert(final BlockPos pos) {
        if (anyHistoryMatch(pos)) {
            final BlockPos orig = getFromHistory(pos);
            final HistoryInfo info = blockBreakHistory.get(orig);
            if (info.brokenPreviously) {
                info.counter++;
                info.brokenPreviously = false;
            }
            //System.out.println("insert " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " c: " + getHistoryCount(pos));
        } else {
            blockBreakHistory.put(pos, new HistoryInfo());
        }


    }

    private long getHistoryCount(final BlockPos pos) {
        if(!anyHistoryMatch(pos)) {
            return 0;
        }

        return blockBreakHistory.get(getFromHistory(pos)).counter;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (snake == null) snake = new Snake();
        snake.tick();
        if (snake.passedLimits() && snake.getRunAwayCommand() != null) {
            return snake.getRunAwayCommand();
        }
        //snake.printCurrent();

        protectItemOfMissing();
        //if (!Baritone.getAltoClefSettings().isItemProtected(Items.DIRT))
        //Baritone.getAltoClefSettings().protectItem(Items.DIRT);
        //Baritone.getAltoClefSettings().getProtectedItems().forEach(e -> System.out.println("PROTECTED: " + e.toString()));
        approxPlaceable = approxPlaceable(36);
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            //System.out.println("a0");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (Baritone.settings().buildInLayers.value) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (Baritone.settings().layerOrder.value) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer;
            } else {
                maxYInclusive = layer - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }

        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (!recalc(bcc)) {
            if (Baritone.settings().buildInLayers.value && layer < realSchematic.heightY()) {
                logDirect("Starting layer " + layer);
                layer++;
                //System.out.println("a1");
                return onTick(calcFailed, isSafeToCancel);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().desktopNotifications.value && Baritone.settings().notificationOnBuildFinished.value) {
                    NotificationHelper.notify("Done building", false);
                }

                if (this.fromAltoclef) {
                    this.fromAltoclefFinished = true;
                }

                //onLostControl();
                reset();

                if (this.fromAltoclefFinished) {
                    this.stateStack.clear();
                }

                //System.out.println("a2");
                return null;
            }
            // build repeat time
            layer = 0;
            origin = new BlockPos(origin).offset(repeat);
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            //System.out.println("a3");
            return onTick(calcFailed, isSafeToCancel);
        }

        /////////////////////////
        /*
        final long thisTime = System.currentTimeMillis();

        if (thisTime - systemMillisOfPrevRecalc >= 3000) {
            recalcCounter = 0;
            //logDebug("resetting recalc counter");
            System.out.println("resetting recalc counter");
        } else {
            recalcCounter++;
        }

        if (recalcCounter > 300 && blockBreakHistory.size() > 0) {
            blockBreakHistory.clear();
            System.out.println("clearing blockBreakHistory " + recalcCounter);
            //logDebug("clearing block break history");
        }

        systemMillisOfPrevRecalc = thisTime;

        //System.out.println("recalc");

        if (Baritone.settings().distanceTrim.value) {
            trim();
        }*/
        /////////////////////////

        Optional<Tuple<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        if (toBreak.isPresent() && isSafeToCancel && ctx.player().isOnGround()) {
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            Rotation rot = toBreak.get().getB();
            BetterBlockPos pos = toBreak.get().getA();
            baritone.getLookBehavior().updateTarget(rot, true);
            MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
            if (ctx.player().isCrouching()) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
                //System.out.println("LEFT A " + pos.x + " " + pos.y + " " + pos.z);
                if (anyHistoryMatch(pos))
                    noteRemoval(pos);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            //System.out.println("a");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        if (!Baritone.getAltoClefSettings().isInteractionPaused() && toPlace.isPresent() && isSafeToCancel && ctx.player().isOnGround() && ticks <= 0) {
            Rotation rot = toPlace.get().rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().selected = toPlace.get().hotbarSelection;
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if ((ctx.isLookingAt(toPlace.get().placeAgainst) && ((BlockHitResult) ctx.objectMouseOver()).getDirection().equals(toPlace.get().side)) || ctx.playerRotations().isReallyCloseTo(rot)) {
                //System.out.println("RIGHT A");
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            //System.out.println("goal");
            //System.out.println("b");
            stopProtectItemOfMissing();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (!Baritone.getAltoClefSettings().isInteractionPaused() && Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains);
                        //System.out.println("c");
                        break outer;
                    }
                }
            }
        }

        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null) {
                if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer < realSchematic.heightY()) {
                    logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                    layer++;
                    return onTick(calcFailed, isSafeToCancel);
                }
                logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        //System.out.println("big goal");
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }


/*
    public boolean assumingStuckOnPathing() {

    }*/

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    private void trim() {
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (valid(bcc.bsi.get0(x, y, z), desired, false) || breakLimitExceeded(new BlockPos(x, y, z))) {
                            incorrectPositions.remove(pos);
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            incorrectPositions.add(pos);
                            observedCompleted.remove(BetterBlockPos.longHash(pos));
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (valid(bcc.bsi.get0(blockX, blockY, blockZ), schematic.desiredState(x, y, z, current, this.approxPlaceable), false) || breakLimitExceeded(new BlockPos(x, y, z))) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else {
                            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    //private BlockPos specialBreak = null;

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        //stopProtectItemOfMissing();
        missing.clear();
        incorrectPositions.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                if (approxPlaceable.contains(bcc.getSchematic(pos.x, pos.y, pos.z, state))) {
                    placeable.add(pos);
                } else {
                    BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                    if (desired != null) {
                        missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                    }
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (!MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        flowingLiquids.add(pos);
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        List<Goal> toBreak = new ArrayList<>();
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));

        List<Goal> toPlace = new ArrayList<>();
        placeable.forEach(pos -> {
            if (!placeable.contains(pos.below()) && !placeable.contains(pos.below(2))) {
                toPlace.add(placementGoal(pos, bcc));
            }
        });
        sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));

        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            if (!missing.isEmpty()) {
                this.pausedBecauseOfMissingMaterials = true;
                //System.out.println("prot called");
                protectItemOfMissing();
            }

            if (logMissing && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }

        //if (!missing.isEmpty()) {
        this.pausedBecauseOfMissingMaterials = false;
        //stopProtectItemOfMissing();
        //}

        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            //noinspection ConstantConditions
            if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing)) && placementPlausible(pos, bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current))) {
                //System.out.println("placement GoalAdjacent");
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
        }
        noteInsert(pos);
        //BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().noteInsert(src);
        //System.out.println("placement GoalPlace");
        return new GoalPlace(pos);
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            //System.out.println("GoalBreak JankyGoalComposite");
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        //TODO: maybe noteRemoval here?
        //System.out.println("GoalBreak");
        return new GoalBreak(pos);
    }

    @Override
    public void setAboveBreak(BlockPos pos) {
        this.aboveBreak = pos;
    }

    @Override
    public BlockPos getAboveBreak() {
        return this.aboveBreak;
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y - 2 && z == this.z) {
                final BlockPos ppos = Minecraft.getInstance().player.blockPosition();

                if (ppos.getX() == x && ppos.getY() == y && ppos.getZ() == z) {
                    BlockPos aboveGoalPos = new BlockPos(this.x, this.y + 1, this.z);
                    BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().setAboveBreak(aboveGoalPos);
                }
            }

            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }
    }

    @Override
    public void onLostControl() {
        if (this.fromAltoclef && this.stateStack.isEmpty()) {
            pushState();
            //System.out.println("BARITONE: PUSHED FROM LOSTCONTROL");
            //System.out.println("BARITONE: PUSHED!");
        }

        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
        origin = null;
        //even if more blocks are blacklisted than required, it is expected that the sourcing trip only starts if the bot fails to add *any* new resources to the structure.
        //since only that amount is specifically sourced, we just blacklist any occurrence.
        //stopProtectItemOfMissing();
        missing = null;
        schemSize = null;
        fromAltoclef = false;
        active = false;
        blockBreakHistory.clear();
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            result.add(((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {})));
            // </toxic cloud>
        }
        return result;
    }

    private boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current == null) {
            return true; // No idea why but current might be null..... oof
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }

        if (desired.getBlock() instanceof SlabBlock) {
            return true;
        }
        if (current.getBlock().defaultBlockState().equals(desired.getBlock().defaultBlockState())) {
            if (current.getBlock() instanceof CrossCollisionBlock) {
                return true;
            }
        }

        /*
        if (!current.equals(desired) && current.equals(desired.getBlock().defaultBlockState())) { //hmm, stays only for testing
            return true;
        }*/

        return current.equals(desired);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * 2; // we're going to have to break it eventually
                }
                if (placeable.contains(sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 3;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if (!allowBreak || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
