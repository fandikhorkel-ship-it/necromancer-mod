package com.necromancermod.entity;

import com.necromancermod.registry.ModEntities;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Modra energeticka koule vystrelena Necromancerem.
 * Leti rovne konstantni rychlosti (bez zrychleni a bez gravitace), zpusobi jen zraneni jako skeleton sip.
 */
public class NecromancerOrb extends AbstractHurtingProjectile {

    public NecromancerOrb(EntityType<? extends NecromancerOrb> type, Level level) {
        super(type, level);
    }

    public NecromancerOrb(Level level, LivingEntity owner) {
        super(ModEntities.ORB.get(), owner, 0.0D, 0.0D, 0.0D, level);
    }

    /** 1.0 = zadne brzdeni; rychlost zustane takova, jakou nastavi shoot(). */
    @Override
    protected float getInertia() {
        return 1.0F;
    }

    @Override
    protected boolean shouldBurn() {
        return false;
    }

    @Override
    protected ParticleOptions getTrailParticle() {
        return ParticleTypes.SOUL_FIRE_FLAME;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean canHitEntity(Entity target) {
        if (target instanceof NecromancerEntity) return false;
        if (target.getTags().contains(NecromancerEntity.MINION_TAG)) return false;
        return super.canHitEntity(target);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && this.tickCount > 100) {
            this.discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (this.level().isClientSide) return;

        Entity target = result.getEntity();
        Entity owner = this.getOwner();
        LivingEntity livingOwner = owner instanceof LivingEntity ? (LivingEntity) owner : null;

        // Podobne jako sip od skeletona: 3 (easy) / 4 (normal) / 5 (hard)
        float damage = 2.0F + (float) this.level().getDifficulty().getId();
        target.hurt(this.damageSources().mobProjectile(this, livingOwner), damage);
        impactEffect();
        this.discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (!this.level().isClientSide) {
            impactEffect();
            this.discard();
        }
    }

    private void impactEffect() {
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.getX(), this.getY(), this.getZ(),
                    10, 0.12D, 0.12D, 0.12D, 0.03D);
        }
    }
}
