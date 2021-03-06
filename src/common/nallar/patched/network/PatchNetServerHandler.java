package nallar.patched.network;

import nallar.tickthreading.patcher.Declare;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.NetServerHandler;
import net.minecraft.network.packet.Packet13PlayerLookMove;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

public abstract class PatchNetServerHandler extends NetServerHandler {
	@Declare
	public volatile int teleported_;
	@Declare
	public double tpPosX_;
	@Declare
	public double tpPosY_;
	@Declare
	public int clientDimension_;
	private double tpPosZ;

	public void construct() {
		teleported = 25;
	}

	@Override
	@Declare
	public void setHasMoved() {
		hasMoved = true;
	}

	@Override
	@Declare
	public void handleTeleport(double x, double y, double z) {
		if (teleported < -1 && playerEntity.getDistanceSq(x, y, z) < 1000) {
			return;
		}
		teleported = 20;
		hasMoved = false;
		lastPosX = tpPosX = x;
		lastPosY = tpPosY = y;
		lastPosZ = tpPosZ = z;
		playerEntity.fallDistance = -1;
	}

	@Override
	@Declare
	public void updatePositionAfterTP(float yaw, float pitch) {
		if (Double.isNaN(tpPosX)) {
			return;
		}
		double x = tpPosX;
		double y = playerEntity.posY;
		double z = tpPosZ;
		lastPosX = x;
		lastPosY = y;
		lastPosZ = z;
		playerEntity.fallDistance = -1;
		playerEntity.setPositionAndRotation(x, y, z, yaw, pitch);
		sendPacketToPlayer(new Packet13PlayerLookMove(x, y + 1.6200000047683716D, y, z, yaw, pitch, false));
		((WorldServer) playerEntity.worldObj).getPlayerManager().updateMountedMovingPlayer(playerEntity);
	}

	public PatchNetServerHandler(MinecraftServer par1, INetworkManager par2, EntityPlayerMP par3) {
		super(par1, par2, par3);
	}
}
