package com.necromancermod.entity;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Necromancer z Minecraft Dungeons.
 *
 * Chovani:
 *  - kdyz se hrac priblizi na dosah blizkeho boje, uteka (AvoidEntityGoal),
 *  - v bezpecne vzdalenosti vyvolava zombie / skeletony (vanilla moby, s brnenim podle obtiznosti),
 *    ale jen kdyz ma kolem sebe malo minionu,
 *  - jinak strili modre energeticke koule; kdyz ho hrac zahnal do kouta, strili skoro porad.
 */
public class NecromancerEntity extends Monster {

    public static final String MINION_TAG = "necromancer_minion";

    public static final int STATE_NONE = 0;
    public static final int STATE_SHOOT = 1;
    public static final int STATE_SUMMON = 2;

    /** Delky animaci v tickach (projectile 1.54 s, summon 1.83 s). */
    private static final int SHOOT_TOTAL_TICKS = 31;
    private static final int SHOOT_FIRE_TICK = 17;
    private static final int SUMMON_TOTAL_TICKS = 38;
    private static final int SUMMON_SPAWN_TICK = 20;

    private static final int MAX_MINIONS = 6;
    private static final int RESUMMON_BELOW = 3;
    private static final double FLEE_DISTANCE = 5.0D;

    private static final EntityDataAccessor<Integer> DATA_STATE =
            SynchedEntityData.defineId(NecromancerEntity.class, EntityDataSerializers.INT);

    /** Pouze klient: tickCount v okamziku, kdy se zmenil stav animace. */
    public int animStartTick;

    private int shootCooldown = 50;
    private int summonCooldown = 20;

    public NecromancerEntity(EntityType<? extends NecromancerEntity> type, Level level) {
        super(type, level);
        this.xpReward = 40;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 100.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    // ------------------------------------------------------------------ data / stav animace

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_STATE, STATE_NONE);
    }

    public int getAnimState() {
        return this.entityData.get(DATA_STATE);
    }

    public void setAnimState(int state) {
        this.entityData.set(DATA_STATE, state);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_STATE.equals(key)) {
            this.animStartTick = this.tickCount;
        }
    }

    // ------------------------------------------------------------------ AI

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // utek pred hracem v dosahu blizkeho boje
        this.goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Player.class, (float) FLEE_DISTANCE, 1.0D, 1.35D));
        this.goalSelector.addGoal(2, new SummonGoal(this));
        this.goalSelector.addGoal(2, new OrbShootGoal(this));
        this.goalSelector.addGoal(3, new KeepRangeGoal(this));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (this.shootCooldown > 0) this.shootCooldown--;
        if (this.summonCooldown > 0) this.summonCooldown--;
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, SpawnGroupData spawnData, CompoundTag tag) {
        this.setPersistenceRequired();
        return super.finalizeSpawn(level, difficulty, reason, spawnData, tag);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // vlastni miniony a vlastni koule mu neublizuji
        Entity attacker = source.getEntity();
        if (attacker != null && attacker.getTags().contains(MINION_TAG)) return false;
        if (source.getDirectEntity() instanceof NecromancerOrb) return false;
        return super.hurt(source, amount);
    }

    @Override
    public MobType getMobType() {
        return MobType.UNDEAD;
    }

    // ------------------------------------------------------------------ zvuky (vanilla skeleton)

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SKELETON_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SKELETON_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SKELETON_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SKELETON_STEP, 0.15F, 1.0F);
    }

    // ------------------------------------------------------------------ utoky

    /** Hrac je tak blizko, ze se Necromancer povazuje za zahnaneho do kouta. */
    private boolean isCornered() {
        LivingEntity target = this.getTarget();
        return target != null && this.distanceToSqr(target) < 6.0D * 6.0D;
    }

    private void shootOrb(LivingEntity target) {
        if (this.level().isClientSide) return;

        Vec3 look = this.getViewVector(1.0F);
        double sx = this.getX() + look.x * 0.9D;
        double sy = this.getEyeY() - 0.15D;
        double sz = this.getZ() + look.z * 0.9D;
        double dx = target.getX() - sx;
        double dy = target.getY(0.6D) - sy;
        double dz = target.getZ() - sz;

        NecromancerOrb orb = new NecromancerOrb(this.level(), this);
        orb.setPos(sx, sy, sz);
        float inaccuracy = 14.0F - (float) this.level().getDifficulty().getId() * 4.0F;
        orb.shoot(dx, dy, dz, 1.2F, inaccuracy);
        this.level().addFreshEntity(orb);
        this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 0.7F);
    }

    private int countMinions() {
        return this.level().getEntitiesOfClass(Mob.class, this.getBoundingBox().inflate(28.0D),
                m -> m.isAlive() && m.getTags().contains(MINION_TAG)).size();
    }

    private void summonMinions() {
        if (!(this.level() instanceof ServerLevel server)) return;

        int toSpawn = Math.min(3, MAX_MINIONS - countMinions());
        if (toSpawn <= 0) return;

        LivingEntity target = this.getTarget();
        for (int i = 0; i < toSpawn; i++) {
            EntityType<? extends Mob> type = this.random.nextBoolean() ? EntityType.ZOMBIE : EntityType.SKELETON;
            Mob minion = type.create(server);
            if (minion == null) continue;

            BlockPos pos = findSpawnPos();
            minion.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, this.random.nextFloat() * 360.0F, 0.0F);
            minion.addTag(MINION_TAG);
            minion.finalizeSpawn(server, server.getCurrentDifficultyAt(pos), MobSpawnType.MOB_SUMMONED, null, null);
            equipArmor(minion);
            if (target != null) minion.setTarget(target);
            server.addFreshEntityWithPassengers(minion);

            server.sendParticles(ParticleTypes.SOUL, pos.getX() + 0.5D, pos.getY() + 0.8D, pos.getZ() + 0.5D,
                    12, 0.25D, 0.5D, 0.25D, 0.03D);
            server.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + 0.5D, pos.getY() + 0.1D, pos.getZ() + 0.5D,
                    8, 0.3D, 0.05D, 0.3D, 0.02D);
        }
    }

    /** Najde volne misto 2-4 bloky od Necromancera. */
    private BlockPos findSpawnPos() {
        for (int attempt = 0; attempt < 16; attempt++) {
            int dx = this.random.nextInt(9) - 4;
            int dz = this.random.nextInt(9) - 4;
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
            BlockPos base = this.blockPosition().offset(dx, 0, dz);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos p = base.offset(0, dy, 0);
                BlockPos below = p.below();
                boolean solidFloor = this.level().getBlockState(below).isFaceSturdy(this.level(), below, Direction.UP);
                boolean freeBody = this.level().getBlockState(p).getCollisionShape(this.level(), p).isEmpty();
                boolean freeHead = this.level().getBlockState(p.above()).getCollisionShape(this.level(), p.above()).isEmpty();
                if (solidFloor && freeBody && freeHead) return p;
            }
        }
        return this.blockPosition();
    }

    /** Miniony dostanou brneni podle obtiznosti (helma vzdy, aby nehorely na slunci). */
    private void equipArmor(Mob mob) {
        int difficulty = this.level().getDifficulty().getId();
        float roll = this.random.nextFloat();

        Item[] set;
        if (difficulty >= 3) {
            set = roll < 0.2F ? diamondArmor() : (roll < 0.7F ? ironArmor() : chainArmor());
        } else if (difficulty == 2) {
            set = roll < 0.45F ? ironArmor() : (roll < 0.85F ? chainArmor() : leatherArmor());
        } else {
            set = roll < 0.4F ? chainArmor() : leatherArmor();
        }

        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < slots.length; i++) {
            if (i == 0 || this.random.nextFloat() < 0.65F) {
                mob.setItemSlot(slots[i], new ItemStack(set[i]));
            }
        }
    }

    private static Item[] leatherArmor() {
        return new Item[]{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
    }

    private static Item[] chainArmor() {
        return new Item[]{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS};
    }

    private static Item[] ironArmor() {
        return new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
    }

    private static Item[] diamondArmor() {
        return new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
    }

    // ------------------------------------------------------------------ goaly

    /** Kouzli energetickou kouli (animace "projectile"). Funguje i za behu (utek + strelba). */
    private static class OrbShootGoal extends Goal {
        private final NecromancerEntity mob;
        private int castTick;

        OrbShootGoal(NecromancerEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.mob.getTarget();
            return target != null
                    && target.isAlive()
                    && this.mob.shootCooldown <= 0
                    && this.mob.getAnimState() == STATE_NONE
                    && this.mob.distanceToSqr(target) <= 26.0D * 26.0D
                    && this.mob.hasLineOfSight(target);
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = this.mob.getTarget();
            return target != null && target.isAlive() && this.castTick < SHOOT_TOTAL_TICKS;
        }

        @Override
        public void start() {
            this.castTick = 0;
            this.mob.setAnimState(STATE_SHOOT);
        }

        @Override
        public void tick() {
            LivingEntity target = this.mob.getTarget();
            if (target != null) {
                this.mob.getLookControl().setLookAt(target, 40.0F, 40.0F);
            }
            this.castTick++;
            if (this.castTick == SHOOT_FIRE_TICK && target != null) {
                this.mob.shootOrb(target);
            }
        }

        @Override
        public void stop() {
            this.mob.setAnimState(STATE_NONE);
            // zahnany do kouta = spamuje koule
            this.mob.shootCooldown = this.mob.isCornered() ? 8 : 45;
            this.castTick = 0;
        }
    }

    /** Vyvola zombie / skeletony (animace "summon"), kdyz je hrac v bezpecne vzdalenosti a miniony chybi. */
    private static class SummonGoal extends Goal {
        private final NecromancerEntity mob;
        private int tick;

        SummonGoal(NecromancerEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.mob.getTarget();
            return target != null
                    && target.isAlive()
                    && this.mob.summonCooldown <= 0
                    && this.mob.getAnimState() == STATE_NONE
                    && this.mob.distanceToSqr(target) > 6.0D * 6.0D
                    && this.mob.countMinions() < RESUMMON_BELOW;
        }

        @Override
        public boolean canContinueToUse() {
            return this.tick < SUMMON_TOTAL_TICKS;
        }

        @Override
        public boolean isInterruptable() {
            return false;
        }

        @Override
        public void start() {
            this.tick = 0;
            this.mob.setAnimState(STATE_SUMMON);
            this.mob.getNavigation().stop();
            this.mob.playSound(SoundEvents.SKELETON_AMBIENT, 1.5F, 0.6F);
        }

        @Override
        public void tick() {
            LivingEntity target = this.mob.getTarget();
            if (target != null) {
                this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            }
            this.tick++;
            if (this.tick == SUMMON_SPAWN_TICK) {
                this.mob.summonMinions();
            }
        }

        @Override
        public void stop() {
            this.mob.setAnimState(STATE_NONE);
            this.mob.summonCooldown = 140 + this.mob.getRandom().nextInt(60);
            this.tick = 0;
        }
    }

    /** Priblizi se k cili, jen kdyz je daleko nebo za prekazkou; jinak stoji a strili. */
    private static class KeepRangeGoal extends Goal {
        private final NecromancerEntity mob;
        private int repath;

        KeepRangeGoal(NecromancerEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.mob.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public void start() {
            this.repath = 0;
        }

        @Override
        public void stop() {
            this.mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            LivingEntity target = this.mob.getTarget();
            if (target == null) return;
            double distSqr = this.mob.distanceToSqr(target);
            boolean seen = this.mob.hasLineOfSight(target);
            if (distSqr > 16.0D * 16.0D || !seen) {
                if (--this.repath <= 0) {
                    this.repath = 10;
                    this.mob.getNavigation().moveTo(target, 1.0D);
                }
            } else {
                this.mob.getNavigation().stop();
            }
        }
    }
}
