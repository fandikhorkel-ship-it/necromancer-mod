package com.necromancermod;

import com.necromancermod.entity.NecromancerEntity;
import com.necromancermod.registry.ModEntities;
import com.necromancermod.registry.ModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = NecromancerMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEvents {

    @SubscribeEvent
    public static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.NECROMANCER.get(), NecromancerEntity.createAttributes().build());
    }

    @SubscribeEvent
    public static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.NECROMANCER_SPAWN_EGG);
        }
    }
}
