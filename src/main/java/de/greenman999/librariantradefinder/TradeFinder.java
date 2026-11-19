package de.greenman999.librariantradefinder;

import de.greenman999.librariantradefinder.config.TradeFinderConfig;
import de.greenman999.librariantradefinder.util.HudUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Cycle: CHECK (open villager's trades) -> BREAK (break lectern) -> PLACE (place lectern) -> CHECK ...
 *
 * Every step only happens when the previous one is CONFIRMED:
 *  - we only click the villager when the crosshair is really on the villager,
 *  - we click once and then wait for the offers packet (no spam while waiting),
 *  - we only break the lectern while the crosshair is really on it,
 *  - we only place after the lectern is really gone, aiming at the top face of the block below.
 */
@SuppressWarnings("DuplicatedCode")
public class TradeFinder {

    public static volatile TradeState state = TradeState.IDLE;
    public static Villager villager = null;
    public static BlockPos lecternPos = null;

    public static boolean searchAll = true;

    // When searching a single enchantment
    public static Enchantment enchantment = null;
    public static int maxBookPrice = 0;
    public static int minLevel = 0;

    public static int tries = 0;

    private static final int AIM_TIMEOUT = 100;          // ticks to get the crosshair on a target
    private static final int OFFERS_TIMEOUT = 40;        // ticks to wait for the villager's trade list
    private static final int JOB_TIMEOUT = 600;          // ticks to wait for the villager to take the lectern
    private static final int BREAK_TIMEOUT = 400;        // ticks to break the lectern

    private static TradeState lastState = TradeState.IDLE;
    private static int stateTicks = 0;
    private static int cooldown = 0;
    private static int offersRetries = 0;
    private static int actionFailures = 0;
    private static int breakSlot = -1;

    public static void stop() {
        state = TradeState.IDLE;
        lastState = TradeState.IDLE;

        enchantment = null;
        maxBookPrice = 0;
        minLevel = 0;
        tries = 0;

        stateTicks = 0;
        cooldown = 0;
        offersRetries = 0;
        actionFailures = 0;
        breakSlot = -1;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) {
            mc.gui.setOverlayMessage(Component.literal(""), false);
        }
    }

    private static void fail(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            HudUtils.chatMessage(HudUtils.text(message, ChatFormatting.RED));
        }
        stop();
    }

    private static int slowExtra() {
        return LibrarianTradeFinder.getConfig().slowMode ? 4 : 0;
    }

    private static void begin() {
        Minecraft mc = Minecraft.getInstance();
        tries = 0;
        stateTicks = 0;
        cooldown = 0;
        offersRetries = 0;
        actionFailures = 0;
        lastState = TradeState.IDLE;
        breakSlot = mc.player != null ? mc.player.getInventory().getSelectedSlot() : -1;
        state = TradeState.CHECK;
    }

    public static int searchList() {
        if (TradeFinderConfig.INSTANCE.enchantments.values().stream().noneMatch(e -> e.enabled)) {
            HudUtils.chatMessage(HudUtils.textTranslatable(ChatFormatting.RED, "commands.tradefinder.search.no-enchantments"));
            return 0;
        }
        if (TradeFinder.villager == null || TradeFinder.lecternPos == null) {
            HudUtils.chatMessage(HudUtils.textTranslatable(ChatFormatting.RED, "commands.tradefinder.start.not-selected"));
            stop();
            return 0;
        }
        searchAll = true;
        begin();
        HudUtils.chatMessage(HudUtils.textTranslatable(ChatFormatting.GREEN, "commands.tradefinder.start.success-list"));
        return 1;
    }

    public static int searchSingle(Enchantment enchantment, int minLevel, int maxBookPrice) {
        if (TradeFinder.villager == null || TradeFinder.lecternPos == null) {
            HudUtils.chatMessage(HudUtils.textTranslatable(ChatFormatting.RED, "commands.tradefinder.start.not-selected"));
            stop();
            return 0;
        }
        searchAll = false;
        begin();
        // begin() does not touch these, set them after it
        TradeFinder.enchantment = enchantment;
        TradeFinder.minLevel = Math.min(minLevel, enchantment.getMaxLevel());
        TradeFinder.maxBookPrice = maxBookPrice;

        HudUtils.chatMessage(HudUtils.textTranslatable(ChatFormatting.GREEN, "commands.tradefinder.start.success-single"));
        return 1;
    }

    public static boolean select() {
        Minecraft mc = Minecraft.getInstance();
        HitResult hitResult = null;
        if (Minecraft.getInstance().player != null) {
            hitResult = Minecraft.getInstance().player.pick(3.0, 0.0F, false);
        }
        if (hitResult != null && (!(hitResult.getType().equals(HitResult.Type.BLOCK)) || hitResult.getType().equals(HitResult.Type.ENTITY))) {
            HudUtils.chatMessage(HudUtils.textTranslatable(ChatFormatting.RED, "commands.tradefinder.select.not-looking-at-lectern"));
            return false;
        }
        BlockPos blockPos = null;
        if (hitResult != null) {
            blockPos = ((BlockHitResult) hitResult).getBlockPos();
        }
        Block block = null;
        if (Minecraft.getInstance().level != null) {
            block = Minecraft.getInstance().level.getBlockState(blockPos).getBlock();
        }
        if (!(block instanceof LecternBlock)) {
            HudUtils.chatMessage(HudUtils.textTranslatable(ChatFormatting.RED, "commands.tradefinder.select.not-looking-at-lectern"));
            return false;
        }

        double closestDistance = Double.POSITIVE_INFINITY;
        Entity closestEntity = null;

        assert Minecraft.getInstance().level != null;
        for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {
            Vec3 entityPos = entity.position();
            if (blockPos != null && entity instanceof Villager && ((Villager) entity).getVillagerData().profession().is(VillagerProfession.LIBRARIAN) && entityPos.distanceTo(blockPos.getCenter()) < closestDistance) {
                closestDistance = entityPos.distanceTo(blockPos.getCenter());
                closestEntity = entity;
            }
        }

        Villager foundVillager = (Villager) closestEntity;
        if (foundVillager == null) {
            HudUtils.chatMessage(HudUtils.textTranslatable(ChatFormatting.RED, "commands.tradefinder.select.no-librarian-found"));
            return false;
        }

        villager = foundVillager;
        lecternPos = blockPos;

        HudUtils.chatMessage(HudUtils.textTranslatable(ChatFormatting.GREEN, "commands.tradefinder.select.success"));
        return true;
    }

    public static void selectManual() {
        villager = null;
        lecternPos = null;
        state = TradeState.SELECT_MANUAL;
    }

    // ------------------------------------------------------------------------------------------
    // Called on the client thread by ClientConnectionMixin when the villager's trade list arrives
    // ------------------------------------------------------------------------------------------
    public static void onOffers(Iterable<MerchantOffer> offers) {
        if (state != TradeState.WAITING_FOR_PACKET) return;

        MerchantOffer foundOffer = null;
        Enchantment foundEnchantment = null;
        int foundLevel = 0;

        search:
        for (MerchantOffer tradeOffer : offers) {
            if (!tradeOffer.getResult().getItem().equals(Items.ENCHANTED_BOOK)) continue;
            for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(tradeOffer.getResult()).entrySet()) {
                Enchantment offered = entry.getKey().value();
                int level = entry.getIntValue();
                int maxPrice;
                int wantedLevel;

                if (searchAll) {
                    TradeFinderConfig.EnchantmentOption option = LibrarianTradeFinder.getConfig().enchantments.get(offered);
                    if (option == null || !option.isEnabled()) continue;
                    maxPrice = option.getMaxPrice();
                    wantedLevel = option.getLevel();
                } else {
                    Enchantment wanted = TradeFinder.enchantment;
                    if (wanted == null) continue;
                    if (!Enchantment.getFullname(Holder.direct(offered), offered.getMaxLevel()).equals(
                            Enchantment.getFullname(Holder.direct(wanted), offered.getMaxLevel()))) continue;
                    maxPrice = maxBookPrice;
                    wantedLevel = minLevel;
                }

                if (tradeOffer.getBaseCostA().getCount() <= maxPrice && level >= wantedLevel) {
                    foundOffer = tradeOffer;
                    foundEnchantment = offered;
                    foundLevel = level;
                    break search;
                }
            }
        }

        offersRetries = 0;
        actionFailures = 0;

        if (foundOffer != null) {
            int attempts = tries;
            int price = foundOffer.getBaseCostA().getCount();
            Enchantment enchant = foundEnchantment;
            int level = foundLevel;
            stop();

            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(
                        Component.translatable(
                                "librarian-trade-finder.found",
                                Enchantment.getFullname(Holder.direct(enchant), level),
                                price,
                                Component.literal(String.valueOf(attempts))
                                        .withStyle(style -> style.withColor(0xcc1141))
                        ).withStyle(ChatFormatting.GREEN)
                );
            }
        } else {
            tries++;
            state = TradeState.BREAK;
            cooldown = 1 + slowExtra();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Main loop, once per client tick
    // ------------------------------------------------------------------------------------------
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();

        if (state == TradeState.IDLE) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) return;

        if (state == TradeState.SELECT_MANUAL) {
            HudUtils.overlayMessage(HudUtils.textTranslatable(ChatFormatting.GRAY, "librarian-trade-finder.actionbar.status.select-manual"), false);
            if (villager != null && lecternPos != null) {
                state = TradeState.IDLE;
            }
            return;
        }

        if (villager == null || lecternPos == null) {
            fail("No villager / lectern selected.");
            return;
        }
        if (villager.isRemoved()) {
            fail("The villager is gone (unloaded or dead). Select again.");
            return;
        }

        if (state != lastState) {
            lastState = state;
            stateTicks = 0;
            actionFailures = 0;
        }
        stateTicks++;

        switch (state) {
            case CHECK -> HudUtils.overlayMessage(HudUtils.textTranslatable(ChatFormatting.GRAY, "librarian-trade-finder.actionbar.status.check", tries), false);
            case BREAK -> HudUtils.overlayMessage(HudUtils.textTranslatable(ChatFormatting.GRAY, "librarian-trade-finder.actionbar.status.break", tries), false);
            case PLACE -> HudUtils.overlayMessage(HudUtils.textTranslatable(ChatFormatting.GRAY, "librarian-trade-finder.actionbar.status.place", tries), false);
            default -> {
            }
        }

        // Like a real player: don't act while some GUI is open
        if (mc.screen != null) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        switch (state) {
            case CHECK -> tickCheck(mc, player);
            case WAITING_FOR_PACKET -> tickWaiting();
            case BREAK -> tickBreak(mc, player);
            case PLACE -> tickPlace(mc, player);
            default -> {
            }
        }
    }

    private static void tickCheck(Minecraft mc, LocalPlayer player) {
        // After the lectern is back, the villager needs a moment to take the job again.
        if (!villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)) {
            if (stateTicks > JOB_TIMEOUT) {
                fail("The villager did not take the lectern back. Is it reachable / is the lectern placed?");
            }
            return;
        }

        // Aim at the villager. Only click when the crosshair is REALLY on that villager.
        if (!(mc.hitResult instanceof EntityHitResult entityHit) || entityHit.getEntity().getId() != villager.getId()) {
            if (stateTicks > AIM_TIMEOUT) {
                fail("Can't aim at the villager (too far or something is in the way).");
                return;
            }
            RotationTools.stepTowards(player, villager.getBoundingBox().getCenter());
            return;
        }

        InteractionResult result = mc.gameMode.interact(player, villager, entityHit, InteractionHand.MAIN_HAND);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            offersRetries = 0;
            state = TradeState.WAITING_FOR_PACKET;   // one click, then wait. No more clicks until the offers arrive.
        } else if (++actionFailures > 5) {
            fail("The villager does not react to clicks.");
        } else {
            cooldown = 2;
        }
    }

    private static void tickWaiting() {
        if (stateTicks <= OFFERS_TIMEOUT) return;

        if (++offersRetries > 3) {
            fail("The server did not send the villager's trades.");
            return;
        }
        state = TradeState.CHECK;   // try the villager again (one click)
        cooldown = 5 + slowExtra();
    }

    private static void tickBreak(Minecraft mc, LocalPlayer player) {
        Inventory inventory = player.getInventory();

        // Back to the tool slot (we switch to the lectern slot while placing)
        if (breakSlot >= 0 && inventory.getSelectedSlot() != breakSlot) {
            inventory.setSelectedSlot(breakSlot);
            cooldown = 2;
            return;
        }

        // Lectern gone -> next step
        if (!(mc.level.getBlockState(lecternPos).getBlock() instanceof LecternBlock)) {
            mc.gameMode.stopDestroyBlock();
            state = TradeState.PLACE;
            cooldown = 3 + slowExtra();
            return;
        }

        if (stateTicks > BREAK_TIMEOUT) {
            fail("Couldn't break the lectern.");
            return;
        }

        ItemStack mainHand = inventory.getSelectedItem();
        if (mainHand.getItem() instanceof AxeItem) {
            int remainingDurability = mainHand.getMaxDamage() - mainHand.getDamageValue();
            if (remainingDurability <= 5 && LibrarianTradeFinder.getConfig().preventAxeBreaking) {
                fail("Your axe is about to break. Stopped.");
                return;
            }
        }

        // Only dig while the crosshair is really on the lectern
        if (!(mc.hitResult instanceof BlockHitResult blockHit) || !blockHit.getBlockPos().equals(lecternPos)) {
            RotationTools.stepTowards(player, lecternPos.getCenter());
            return;
        }

        if (mc.gameMode.continueDestroyBlock(lecternPos, blockHit.getDirection())) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static void tickPlace(Minecraft mc, LocalPlayer player) {
        Inventory inventory = player.getInventory();
        BlockPos below = lecternPos.below();

        // Lectern goes in the OFF hand (axe stays in the main hand). If it is not there,
        // fall back to a lectern that is already in the main hand, or one in the hotbar.
        InteractionHand placeHand;
        if (player.getOffhandItem().getItem() == Items.LECTERN) {
            placeHand = InteractionHand.OFF_HAND;
        } else if (inventory.getSelectedItem().getItem() == Items.LECTERN) {
            placeHand = InteractionHand.MAIN_HAND;
        } else {
            int slot = -1;
            for (int i = 0; i < 9; i++) {
                if (inventory.getItem(i).getItem() == Items.LECTERN) {
                    slot = i;
                    break;
                }
            }
            if (slot == -1) {
                fail("No lectern in your off hand. Put lecterns in your off hand (axe in main hand) and start again.");
                return;
            }
            inventory.setSelectedSlot(slot);
            cooldown = 2 + slowExtra();
            return;
        }

        // Wait until the old lectern is really gone before placing a new one
        if (!mc.level.getBlockState(lecternPos).isAir()) {
            if (stateTicks > AIM_TIMEOUT) {
                fail("The lectern spot is not free.");
            }
            return;
        }

        // Aim at the top face of the block below the lectern spot
        Vec3 aim = new Vec3(below.getX() + 0.5, below.getY() + 0.98, below.getZ() + 0.5);
        if (!(mc.hitResult instanceof BlockHitResult blockHit)
                || !blockHit.getBlockPos().equals(below)
                || blockHit.getDirection() != Direction.UP) {
            if (stateTicks > AIM_TIMEOUT) {
                fail("Can't aim at the top of the block under the lectern spot.");
                return;
            }
            RotationTools.stepTowards(player, aim);
            return;
        }

        InteractionResult result = mc.gameMode.useItemOn(player, placeHand, blockHit);
        if (result.consumesAction()) {
            player.swing(placeHand);
            state = TradeState.CHECK;
            cooldown = 2 + slowExtra();
        } else if (++actionFailures > 5) {
            fail("Placing the lectern failed.");
        } else {
            cooldown = 2;
        }
    }
}
