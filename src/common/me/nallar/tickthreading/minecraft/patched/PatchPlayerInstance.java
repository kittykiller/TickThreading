package me.nallar.tickthreading.minecraft.patched;

import me.nallar.tickthreading.patcher.Declare;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.packet.Packet51MapChunk;
import net.minecraft.server.management.PlayerInstance;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkWatchEvent;

public abstract class PatchPlayerInstance extends PlayerInstance {
	private static byte[] unloadSequence;

	public PatchPlayerInstance(PlayerManager par1PlayerManager, int par2, int par3) {
		super(par1PlayerManager, par2, par3);
	}

	@Override
	@Declare
	public ChunkCoordIntPair getLocation() {
		return chunkLocation;
	}

	public static void staticConstruct() {
		unloadSequence = new byte[]{0x78, (byte) 0x9C, 0x63, 0x64, 0x1C, (byte) 0xD9, 0x00, 0x00, (byte) 0x81, (byte) 0x80, 0x01, 0x01};
	}

	@Override
	public void sendThisChunkToPlayer(EntityPlayerMP par1EntityPlayerMP) {
		if (this.playersInChunk.contains(par1EntityPlayerMP)) {
			Packet51MapChunk packet51MapChunk = new Packet51MapChunk();
			packet51MapChunk.includeInitialize = true;
			packet51MapChunk.xCh = chunkLocation.chunkXPos;
			packet51MapChunk.zCh = chunkLocation.chunkZPos;
			packet51MapChunk.yChMax = 0;
			packet51MapChunk.yChMin = 0;
			packet51MapChunk.setData(unloadSequence);
			par1EntityPlayerMP.playerNetServerHandler.sendPacketToPlayer(packet51MapChunk);
			this.playersInChunk.remove(par1EntityPlayerMP);
			par1EntityPlayerMP.loadedChunks.remove(this.chunkLocation);

			MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.UnWatch(chunkLocation, par1EntityPlayerMP));

			if (this.playersInChunk.isEmpty()) {
				long var2 = (long) this.chunkLocation.chunkXPos + 2147483647L | (long) this.chunkLocation.chunkZPos + 2147483647L << 32;
				this.myManager.getChunkWatchers().remove(var2);

				if (this.numberOfTilesToUpdate > 0) {
					this.myManager.playerUpdateLock.lock();
					try {
						this.myManager.getChunkWatcherWithPlayers().remove(this);
					} finally {
						this.myManager.playerUpdateLock.unlock();
					}
				}

				this.myManager.getWorldServer().theChunkProviderServer.unloadChunksIfNotNearSpawn(this.chunkLocation.chunkXPos, this.chunkLocation.chunkZPos);
			}
		} else {
			throw new IllegalStateException("Player " + par1EntityPlayerMP + " was not already watching " + this);
		}
	}

	@Override
	public void flagChunkForUpdate(int par1, int par2, int par3) {
		if (this.numberOfTilesToUpdate == 0) {
			this.myManager.playerUpdateLock.lock();
			try {
				this.myManager.getChunkWatcherWithPlayers().add(this);
			} finally {
				this.myManager.playerUpdateLock.unlock();
			}
		}

		this.field_73260_f |= 1 << (par2 >> 4);

		if (this.numberOfTilesToUpdate < 64) {
			short var4 = (short) (par1 << 12 | par3 << 8 | par2);

			for (int var5 = 0; var5 < this.numberOfTilesToUpdate; ++var5) {
				if (this.locationOfBlockChange[var5] == var4) {
					return;
				}
			}

			this.locationOfBlockChange[this.numberOfTilesToUpdate++] = var4;
		}
	}
}
