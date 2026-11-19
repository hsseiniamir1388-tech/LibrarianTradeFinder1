package de.greenman999.librariantradefinder.mixin;

import de.greenman999.librariantradefinder.TradeFinder;
import de.greenman999.librariantradefinder.TradeState;
import io.netty.channel.ChannelHandlerContext;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(net.fabricmc.api.EnvType.CLIENT)
@Mixin(Connection.class)
public class ClientConnectionMixin {

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void onChannelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet, CallbackInfo ci) {
        // Only touch packets that belong to a trade check WE started. Anything else is left alone.
        if (TradeFinder.state != TradeState.WAITING_FOR_PACKET) return;

        if (packet instanceof ClientboundOpenScreenPacket openScreenPacket) {
            if (openScreenPacket.getType() == MenuType.MERCHANT) {
                int containerId = openScreenPacket.getContainerId();
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    ClientPacketListener connection = mc.getConnection();
                    if (connection != null) {
                        connection.send(new ServerboundContainerClosePacket(containerId));
                    }
                });
                ci.cancel();
            }
        } else if (packet instanceof ClientboundMerchantOffersPacket offersPacket) {
            // Evaluate on the client thread, never on the netty thread.
            Minecraft.getInstance().execute(() -> TradeFinder.onOffers(offersPacket.getOffers()));
        }
    }
}
