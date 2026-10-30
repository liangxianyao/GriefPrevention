/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2012 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.GriefPrevention;

import me.ryanhamshire.GriefPrevention.events.AccrueClaimBlocksEvent;
import org.bukkit.entity.Player;

import java.util.Collection;

//FEATURE: give players claim blocks for playing, as long as they're not away from their computer

//runs every 5 minutes in the main thread, grants blocks per hour / 12 to each online player who appears to be actively playing
class DeliverClaimBlocksTask implements Runnable
{
    private final Player player;
    private final GriefPrevention instance;
    private final int idleThresholdSquared;

    public DeliverClaimBlocksTask(Player player, GriefPrevention instance)
    {
        this.player = player;
        this.instance = instance;
        this.idleThresholdSquared = instance.config_claims_accruedIdleThreshold * instance.config_claims_accruedIdleThreshold;
    }

    @Override
    public void run()
    {
        //if no player specified, this task will create a player-specific task for each online player, scheduled one tick apart
        if (this.player == null)
        {
            @SuppressWarnings("unchecked")
            Collection<Player> players = (Collection<Player>) GriefPrevention.instance.getServer().getOnlinePlayers();

            long i = 0;
            for (Player onlinePlayer : players)
            {
                DeliverClaimBlocksTask newTask = new DeliverClaimBlocksTask(onlinePlayer, instance);
                instance.getServer().getScheduler().scheduleSyncDelayedTask(instance, newTask, i++);
            }

            return; //tasks started for each player
        }

        //deliver claim blocks to the specified player
        if (!this.player.isOnline())
        {
            return; //player is not online to receive claim blocks
        }

        DataStore dataStore = instance.dataStore;
        PlayerData playerData = dataStore.getPlayerData(player.getUniqueId());

        // check if player is idle. considered idle if
        //    in vehicle or is in water (pushed by water)
        //    or has not moved at least defined blocks since last check
        boolean isIdle = false;
        try
        {
            isIdle = player.isInsideVehicle() || player.getLocation().getBlock().isLiquid() ||
                    !(playerData.lastAfkCheckLocation == null || playerData.lastAfkCheckLocation.distanceSquared(player.getLocation()) > idleThresholdSquared);
        }
        catch (IllegalArgumentException ignore) //can't measure distance when to/from are different worlds
        {
        }

        //remember current location for next time
        playerData.lastAfkCheckLocation = player.getLocation();

        try
        {
            //determine how fast blocks accrue for this player //RoboMWM: addons determine this instead
            int[] bonuses = instance.getClaimBlockAccrualBonuses(player);
            int accrualRate = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0L, (long) instance.config_claims_blocksAccruedPerHour_default + bonuses[0]));
            int dailyLimit = instance.config_claims_maxAccruedBlocksPerDay_default == 0 ? 0 :
                    (int) Math.min(Integer.MAX_VALUE, (long) instance.config_claims_maxAccruedBlocksPerDay_default + bonuses[1]);

            //determine idle accrual rate when idle
            if (isIdle)
            {
                if (instance.config_claims_accruedIdlePercent <= 0)
                {
                    GriefPrevention.AddLogEntry(player.getName() + " wasn't active enough to accrue claim blocks this round.", CustomLogEntryTypes.Debug, true);
                    return; //idle accrual percentage is disabled
                }

                accrualRate = (int) (accrualRate * (instance.config_claims_accruedIdlePercent / 100.0D));
            }

            //fire event for addons
            long hourlyParts = (long) accrualRate + playerData.getAccrualRemainder();
            int blocksThisRound = (int) Math.min(Integer.MAX_VALUE, hourlyParts / 6);
            int remainingParts = (int) (hourlyParts % 6);
            AccrueClaimBlocksEvent event = new AccrueClaimBlocksEvent(player, accrualRate, isIdle);
            event.setBlocksToAccrue(blocksThisRound);
            instance.getServer().getPluginManager().callEvent(event);
            if (event.isCancelled())
            {
                GriefPrevention.AddLogEntry(player.getName() + " claim block delivery was canceled by another plugin.", CustomLogEntryTypes.Debug, true);
                return; //event was cancelled
            }

            playerData.setAccrualRemainder(remainingParts);

            //set actual accrual
            accrualRate = event.getBlocksToAccrue();
            if (accrualRate < 0) accrualRate = 0;
            int granted = playerData.accrueBlocksLimited(accrualRate, dailyLimit, bonuses[2]);
            GriefPrevention.AddLogEntry("Delivering " + granted + " blocks to " + player.getName(), CustomLogEntryTypes.Debug, true);

            //Persist daily accrual immediately so a restart cannot reset the daily allowance.
            if (granted > 0 && dailyLimit > 0)
                dataStore.savePlayerData(player.getUniqueId(), playerData);
        }
        catch (Exception e)
        {
            GriefPrevention.AddLogEntry("Problem delivering claim blocks to player " + player.getName() + ":");
            e.printStackTrace();
        }
    }
}
