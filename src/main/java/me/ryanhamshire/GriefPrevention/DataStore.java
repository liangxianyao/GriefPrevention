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

import com.google.common.io.Files;
import com.griefprevention.visualization.BoundaryVisualization;
import com.griefprevention.visualization.VisualizationType;
import me.ryanhamshire.GriefPrevention.events.ClaimModifiedEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimResizeEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimCreatedEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimDeletedEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimExtendEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimTransferEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

//singleton class which manages all GriefPrevention data (except for config options)
public abstract class DataStore
{

    //in-memory cache for player data
    protected ConcurrentHashMap<UUID, PlayerData> playerNameToPlayerDataMap = new ConcurrentHashMap<>();

    //in-memory cache for group (permission-based) data
    protected ConcurrentHashMap<String, Integer> permissionToBonusBlocksMap = new ConcurrentHashMap<>();

    //in-memory cache for claim data
    ArrayList<Claim> claims = new ArrayList<>();
    ConcurrentHashMap<Long, ArrayList<Claim>> chunksToClaimsMap = new ConcurrentHashMap<>();

    //in-memory cache for messages
    private String[] messages;

    //pattern for unique user identifiers (UUIDs)
    protected final static Pattern uuidpattern = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    //next claim ID
    Long nextClaimID = (long) 0;

    //path information, for where stuff stored on disk is well...  stored
    protected final static String dataLayerFolderPath = "plugins" + File.separator + "GriefPreventionData";
    final static String playerDataFolderPath = dataLayerFolderPath + File.separator + "PlayerData";
    final static String configFilePath = dataLayerFolderPath + File.separator + "config.yml";
    final static String messagesFilePath = dataLayerFolderPath + File.separator + "messages.yml";
    final static String softMuteFilePath = dataLayerFolderPath + File.separator + "softMute.txt";
    final static String bannedWordsFilePath = dataLayerFolderPath + File.separator + "bannedWords.txt";

    //the latest version of the data schema implemented here
    protected static final int latestSchemaVersion = 3;

    //reading and writing the schema version to the data store
    abstract int getSchemaVersionFromStorage();

    abstract void updateSchemaVersionInStorage(int versionToSet);

    //current version of the schema of data in secondary storage
    private int currentSchemaVersion = -1;  //-1 means not determined yet

    //video links
    static final String SURVIVAL_VIDEO_URL = "" + ChatColor.DARK_AQUA + ChatColor.UNDERLINE + "bit.ly/mcgpuser" + ChatColor.RESET;
    static final String CREATIVE_VIDEO_URL = "" + ChatColor.DARK_AQUA + ChatColor.UNDERLINE + "bit.ly/mcgpcrea" + ChatColor.RESET;
    static final String SUBDIVISION_VIDEO_URL = "" + ChatColor.DARK_AQUA + ChatColor.UNDERLINE + "bit.ly/mcgpsub" + ChatColor.RESET;

    //list of UUIDs which are soft-muted
    ConcurrentHashMap<UUID, Boolean> softMuteMap = new ConcurrentHashMap<>();

    //world guard reference, if available
    private WorldGuardWrapper worldGuard = null;

    protected int getSchemaVersion()
    {
        if (this.currentSchemaVersion >= 0)
        {
            return this.currentSchemaVersion;
        }
        else
        {
            this.currentSchemaVersion = this.getSchemaVersionFromStorage();
            return this.currentSchemaVersion;
        }
    }

    protected void setSchemaVersion(int versionToSet)
    {
        this.currentSchemaVersion = versionToSet;
        this.updateSchemaVersionInStorage(versionToSet);
    }

    //initialization!
    void initialize() throws Exception
    {
        GriefPrevention.AddLogEntry(this.claims.size() + " total claims loaded.");

        //RoboMWM: ensure the nextClaimID is greater than any other claim ID. If not, data corruption occurred (out of storage space, usually).
        for (Claim claim : this.claims)
        {
            if (claim.id >= nextClaimID)
            {
                GriefPrevention.instance.getLogger().severe("nextClaimID was lesser or equal to an already-existing claim ID!\n" +
                        "This usually happens if you ran out of storage space.");
                GriefPrevention.AddLogEntry("Changing nextClaimID from " + nextClaimID + " to " + claim.id, CustomLogEntryTypes.Debug, false);
                nextClaimID = claim.id + 1;
            }
        }

        //ensure data folders exist
        File playerDataFolder = new File(playerDataFolderPath);
        if (!playerDataFolder.exists())
        {
            playerDataFolder.mkdirs();
        }

        //load up all the messages from messages.yml
        this.loadMessages();
        GriefPrevention.AddLogEntry("Customizable messages loaded.");

        //if converting up from an earlier schema version, write all claims back to storage using the latest format
        if (this.getSchemaVersion() < latestSchemaVersion)
        {
            GriefPrevention.AddLogEntry("Please wait.  Updating data format.");

            for (Claim claim : this.claims)
            {
                this.saveClaim(claim);

                for (Claim subClaim : claim.children)
                {
                    this.saveClaim(subClaim);
                }
            }

            //clean up any UUID conversion work
            if (UUIDFetcher.lookupCache != null)
            {
                UUIDFetcher.lookupCache.clear();
                UUIDFetcher.correctedNames.clear();
            }

            GriefPrevention.AddLogEntry("Update finished.");
        }

        //load list of soft mutes
        this.loadSoftMutes();

        //make a note of the data store schema version
        this.setSchemaVersion(latestSchemaVersion);

        //try to hook into world guard
        try
        {
            this.worldGuard = new WorldGuardWrapper();
            GriefPrevention.AddLogEntry("Successfully hooked into WorldGuard.");
        }
        //if failed, world guard compat features will just be disabled.
        catch (IllegalStateException | IllegalArgumentException | ClassCastException | NoClassDefFoundError ignored) { }
    }

    private void loadSoftMutes()
    {
        File softMuteFile = new File(softMuteFilePath);
        if (softMuteFile.exists())
        {
            BufferedReader inStream = null;
            try
            {
                //open the file
                inStream = new BufferedReader(new FileReader(softMuteFile.getAbsolutePath()));

                //while there are lines left
                String nextID = inStream.readLine();
                while (nextID != null)
                {
                    //parse line into a UUID
                    UUID playerID;
                    try
                    {
                        playerID = UUID.fromString(nextID);
                    }
                    catch (Exception e)
                    {
                        playerID = null;
                        GriefPrevention.AddLogEntry("Failed to parse soft mute entry as a UUID: " + nextID);
                    }

                    //push it into the map
                    if (playerID != null)
                    {
                        this.softMuteMap.put(playerID, true);
                    }

                    //move to the next
                    nextID = inStream.readLine();
                }
            }
            catch (Exception e)
            {
                GriefPrevention.AddLogEntry("Failed to read from the soft mute data file: " + e.toString());
                e.printStackTrace();
            }

            try
            {
                if (inStream != null) inStream.close();
            }
            catch (IOException exception) {}
        }
    }

    public List<String> loadBannedWords()
    {
        try
        {
            File bannedWordsFile = new File(bannedWordsFilePath);
            if (!bannedWordsFile.exists())
            {
                Files.touch(bannedWordsFile);
                String defaultWords =
                        "nigger\nniggers\nniger\nnigga\nnigers\nniggas\n" +
                                "fag\nfags\nfaggot\nfaggots\nfeggit\nfeggits\nfaggit\nfaggits\n" +
                                "cunt\ncunts\nwhore\nwhores\nslut\nsluts\n";
                Files.append(defaultWords, bannedWordsFile, Charset.forName("UTF-8"));
            }

            return Files.readLines(bannedWordsFile, Charset.forName("UTF-8"));
        }
        catch (Exception e)
        {
            GriefPrevention.AddLogEntry("Failed to read from the banned words data file: " + e.toString());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    //updates soft mute map and data file
    boolean toggleSoftMute(UUID playerID)
    {
        boolean newValue = !this.isSoftMuted(playerID);

        this.softMuteMap.put(playerID, newValue);
        this.saveSoftMutes();

        return newValue;
    }

    public boolean isSoftMuted(UUID playerID)
    {
        Boolean mapEntry = this.softMuteMap.get(playerID);
        if (mapEntry == null || mapEntry == Boolean.FALSE)
        {
            return false;
        }

        return true;
    }

    private void saveSoftMutes()
    {
        BufferedWriter outStream = null;

        try
        {
            //open the file and write the new value
            File softMuteFile = new File(softMuteFilePath);
            softMuteFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(softMuteFile));

            for (Map.Entry<UUID, Boolean> entry : softMuteMap.entrySet())
            {
                if (entry.getValue().booleanValue())
                {
                    outStream.write(entry.getKey().toString());
                    outStream.newLine();
                }
            }

        }

        //if any problem, log it
        catch (Exception e)
        {
            GriefPrevention.AddLogEntry("Unexpected exception saving soft mute data: " + e.getMessage());
            e.printStackTrace();
        }

        //close the file
        try
        {
            if (outStream != null) outStream.close();
        }
        catch (IOException exception) {}
    }

    //removes cached player data from memory
    synchronized void clearCachedPlayerData(UUID playerID)
    {
        this.playerNameToPlayerDataMap.remove(playerID);
    }

    //gets the number of bonus blocks a player has from his permissions
    //Bukkit doesn't allow for checking permissions of an offline player.
    //this will return 0 when he's offline, and the correct number when online.
    synchronized public int getGroupBonusBlocks(UUID playerID)
    {
        Player player = GriefPrevention.instance.getServer().getPlayer(playerID);

        if (player == null) return 0;

        int bonusBlocks = 0;

        for (Map.Entry<String, Integer> groupEntry : this.permissionToBonusBlocksMap.entrySet())
        {
            if (player.hasPermission(groupEntry.getKey()))
            {
                bonusBlocks += groupEntry.getValue();
            }
        }

        return bonusBlocks;
    }

    //grants a group (players with a specific permission) bonus claim blocks as long as they're still members of the group
    synchronized public int adjustGroupBonusBlocks(String groupName, int amount)
    {
        Integer currentValue = this.permissionToBonusBlocksMap.get(groupName);
        if (currentValue == null) currentValue = 0;

        currentValue += amount;
        this.permissionToBonusBlocksMap.put(groupName, currentValue);

        //write changes to storage to ensure they don't get lost
        this.saveGroupBonusBlocks(groupName, currentValue);

        return currentValue;
    }

    abstract void saveGroupBonusBlocks(String groupName, int amount);

    public class NoTransferException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        NoTransferException(String message)
        {
            super(message);
        }
    }

    synchronized public void changeClaimOwner(Claim claim, UUID newOwnerID)
    {
        //if it's a subdivision, throw an exception
        if (claim.parent != null)
        {
            throw new NoTransferException("Subdivisions can't be transferred.  Only top-level claims may change owners.");
        }

        //otherwise update information

        //determine current claim owner
        PlayerData ownerData = null;
        if (!claim.isAdminClaim())
        {
            ownerData = this.getPlayerData(claim.ownerID);
        }

        //call event
        ClaimTransferEvent event = new ClaimTransferEvent(claim, newOwnerID);
        Bukkit.getPluginManager().callEvent(event);

        //return if event is cancelled
        if (event.isCancelled()) return;

        //determine new owner
        PlayerData newOwnerData = null;

        if (event.getNewOwner() != null)
        {
            newOwnerData = this.getPlayerData(event.getNewOwner());
        }

        //transfer
        claim.ownerID = event.getNewOwner();
        this.saveClaim(claim);

        //adjust blocks and other records
        if (ownerData != null)
        {
            ownerData.getClaims().remove(claim);
        }

        if (newOwnerData != null)
        {
            newOwnerData.getClaims().add(claim);
        }
    }

    //adds a claim to the datastore, making it an effective claim
    synchronized void addClaim(Claim newClaim, boolean writeToStorage)
    {
        //subdivisions are added under their parent, not directly to the hash map for direct search
        if (newClaim.parent != null)
        {
            if (!newClaim.parent.children.contains(newClaim))
            {
                newClaim.parent.children.add(newClaim);
            }
            newClaim.inDataStore = true;
            if (writeToStorage)
            {
                this.saveClaim(newClaim);
            }
            return;
        }

        //add it and mark it as added
        this.claims.add(newClaim);
        addToChunkClaimMap(newClaim);

        newClaim.inDataStore = true;

        //except for administrative claims (which have no owner), update the owner's playerData with the new claim
        if (!newClaim.isAdminClaim() && writeToStorage)
        {
            PlayerData ownerData = this.getPlayerData(newClaim.ownerID);
            ownerData.getClaims().add(newClaim);
        }

        //make sure the claim is saved to disk
        if (writeToStorage)
        {
            this.saveClaim(newClaim);
        }
    }

    private void addToChunkClaimMap(Claim claim)
    {
        // Subclaims should not be added to chunk claim map.
        if (claim.parent != null) return;

        ArrayList<Long> chunkHashes = claim.getChunkHashes();
        for (Long chunkHash : chunkHashes)
        {
            ArrayList<Claim> claimsInChunk = this.chunksToClaimsMap.get(chunkHash);
            if (claimsInChunk == null)
            {
                this.chunksToClaimsMap.put(chunkHash, claimsInChunk = new ArrayList<>());
            }

            claimsInChunk.add(claim);
        }
    }

    private void removeFromChunkClaimMap(Claim claim)
    {
        ArrayList<Long> chunkHashes = claim.getChunkHashes();
        for (Long chunkHash : chunkHashes)
        {
            ArrayList<Claim> claimsInChunk = this.chunksToClaimsMap.get(chunkHash);
            if (claimsInChunk != null)
            {
                for (Iterator<Claim> it = claimsInChunk.iterator(); it.hasNext(); )
                {
                    Claim c = it.next();
                    if (c.id.equals(claim.id))
                    {
                        it.remove();
                        break;
                    }
                }
                if (claimsInChunk.isEmpty())
                { // if nothing's left, remove this chunk's cache
                    this.chunksToClaimsMap.remove(chunkHash);
                }
            }
        }
    }

    //turns a location into a string, useful in data storage
    private final String locationStringDelimiter = ";";

    String locationToString(Location location)
    {
        StringBuilder stringBuilder = new StringBuilder(location.getWorld().getName());
        stringBuilder.append(locationStringDelimiter);
        stringBuilder.append(location.getBlockX());
        stringBuilder.append(locationStringDelimiter);
        stringBuilder.append(location.getBlockY());
        stringBuilder.append(locationStringDelimiter);
        stringBuilder.append(location.getBlockZ());

        return stringBuilder.toString();
    }

    //turns a location string back into a location
    Location locationFromString(String string, List<World> validWorlds) throws Exception
    {
        //split the input string on the space
        String[] elements = string.split(locationStringDelimiter);

        //expect four elements - world name, X, Y, and Z, respectively
        if (elements.length < 4)
        {
            throw new Exception("Expected four distinct parts to the location string: \"" + string + "\"");
        }

        String worldName = elements[0];
        String xString = elements[1];
        String yString = elements[2];
        String zString = elements[3];

        //identify world the claim is in
        World world = null;
        for (World w : validWorlds)
        {
            if (w.getName().equalsIgnoreCase(worldName))
            {
                world = w;
                break;
            }
        }

        if (world == null)
        {
            throw new Exception("World not found: \"" + worldName + "\"");
        }

        //convert those numerical strings to integer values
        int x = Integer.parseInt(xString);
        int y = Integer.parseInt(yString);
        int z = Integer.parseInt(zString);

        return new Location(world, x, y, z);
    }

    //saves any changes to a claim to secondary storage
    synchronized public void saveClaim(Claim claim)
    {
        assignClaimID(claim);

        this.writeClaimToStorage(claim);
    }

    private void assignClaimID(Claim claim)
    {
        //ensure a unique identifier for the claim which will be used to name the file on disk
        if (claim.id == null || claim.id == -1)
        {
            claim.id = this.nextClaimID;
            this.incrementNextClaimID();
        }
    }

    abstract void writeClaimToStorage(Claim claim);

    //increments the claim ID and updates secondary storage to be sure it's saved
    abstract void incrementNextClaimID();

    //retrieves player data from memory or secondary storage, as necessary
    //if the player has never been on the server before, this will return a fresh player data with default values
    synchronized public PlayerData getPlayerData(UUID playerID)
    {
        //first, look in memory
        PlayerData playerData = this.playerNameToPlayerDataMap.get(playerID);

        //if not there, build a fresh instance with some blanks for what may be in secondary storage
        if (playerData == null)
        {
            playerData = new PlayerData();
            playerData.playerID = playerID;

            //shove that new player data into the hash map cache
            this.playerNameToPlayerDataMap.put(playerID, playerData);
        }

        return playerData;
    }

    abstract PlayerData getPlayerDataFromStorage(UUID playerID);

    //deletes a claim or subdivision
    synchronized public void deleteClaim(Claim claim)
    {
        this.deleteClaim(claim, true, false);
    }

    //deletes a claim or subdivision
    synchronized public void deleteClaim(Claim claim, boolean releasePets)
    {
        this.deleteClaim(claim, true, releasePets);
    }

    synchronized void deleteClaim(Claim claim, boolean fireEvent, boolean releasePets)
    {
        //delete any children
        for (int j = 1; (j - 1) < claim.children.size(); j++)
        {
            this.deleteClaim(claim.children.get(j - 1), releasePets);
        }

        //subdivisions must also be removed from the parent claim child list
        if (claim.parent != null)
        {
            Claim parentClaim = claim.parent;
            parentClaim.children.remove(claim);
        }

        //mark as deleted so any references elsewhere can be ignored
        claim.inDataStore = false;

        //remove from memory
        for (int i = 0; i < this.claims.size(); i++)
        {
            if (claims.get(i).id.equals(claim.id))
            {
                this.claims.remove(i);
                break;
            }
        }

        removeFromChunkClaimMap(claim);

        //remove from secondary storage
        this.deleteClaimFromSecondaryStorage(claim);

        //update player data
        if (claim.ownerID != null)
        {
            PlayerData ownerData = this.getPlayerData(claim.ownerID);
            for (int i = 0; i < ownerData.getClaims().size(); i++)
            {
                if (ownerData.getClaims().get(i).id.equals(claim.id))
                {
                    ownerData.getClaims().remove(i);
                    break;
                }
            }
            this.savePlayerData(claim.ownerID, ownerData);
        }

        if (fireEvent)
        {
            ClaimDeletedEvent ev = new ClaimDeletedEvent(claim);
            Bukkit.getPluginManager().callEvent(ev);
        }

        //optionally set any pets free which belong to the claim owner
        if (releasePets && claim.ownerID != null && claim.parent == null)
        {
            for (Chunk chunk : claim.getChunks())
            {
                Entity[] entities = chunk.getEntities();
                for (Entity entity : entities)
                {
                    if (entity instanceof Tameable pet && pet.isTamed())
                    {
                        AnimalTamer owner = pet.getOwner();
                        if (owner != null && owner.getUniqueId().equals(claim.ownerID))
                        {
                            pet.setTamed(false);
                            pet.setOwner(null);
                            if (pet instanceof InventoryHolder holder) {
                                holder.getInventory().clear();
                            }
                            if (pet instanceof Sittable sittable) {
                                sittable.setSitting(false);
                            }
                        }
                    }
                }
            }
        }
    }

    abstract void deleteClaimFromSecondaryStorage(Claim claim);

    //gets the claim at a specific location
    //ignoreHeight = TRUE means that a location UNDER an existing claim will return the claim
    //cachedClaim can be NULL, but will help performance if you have a reasonable guess about which claim the location is in
    synchronized public Claim getClaimAt(Location location, boolean ignoreHeight, Claim cachedClaim)
    {
        return getClaimAt(location, ignoreHeight, false, cachedClaim);
    }

    /**
     * Get the claim at a specific location.
     *
     * <p>The cached claim may be null, but will increase performance if you have a reasonable idea
     * of which claim is correct.
     *
     * @param location the location
     * @param ignoreHeight whether or not to check containment vertically
     * @param ignoreSubclaims whether or not subclaims should be returned over claims
     * @param cachedClaim the cached claim, if any
     * @return the claim containing the location or null if no claim exists there
     */
    synchronized public Claim getClaimAt(Location location, boolean ignoreHeight, boolean ignoreSubclaims, Claim cachedClaim)
    {
        //check cachedClaim guess first.  if it's in the datastore and the location is inside it, we're done
        if (cachedClaim != null && cachedClaim.inDataStore && cachedClaim.contains(location, ignoreHeight, !ignoreSubclaims))
            return cachedClaim;

        //find a top level claim
        Long chunkID = getChunkHash(location);
        ArrayList<Claim> claimsInChunk = this.chunksToClaimsMap.get(chunkID);
        if (claimsInChunk == null) return null;

        for (Claim claim : claimsInChunk)
        {
            if (claim.inDataStore && claim.contains(location, ignoreHeight, false))
            {
                // If ignoring subclaims, claim is a match.
                if (ignoreSubclaims) return claim;

                //when we find a top level claim, if the location is in one of its subdivisions,
                //return the SUBDIVISION, not the top level claim
                for (int j = 0; j < claim.children.size(); j++)
                {
                    Claim subdivision = claim.children.get(j);
                    if (subdivision.inDataStore && subdivision.contains(location, ignoreHeight, false))
                        return subdivision;
                }

                return claim;
            }
        }

        //if no claim found, return null
        return null;
    }

    //finds a claim by ID
    public synchronized Claim getClaim(long id)
    {
        for (Claim claim : this.claims)
        {
            if (claim.inDataStore)
            {
                if (claim.getID() == id)
                    return claim;
                for (Claim subClaim : claim.children)
                {
                    if (subClaim.getID() == id)
                    return subClaim;
                }
            }
        }

        return null;
    }

    //returns a read-only access point for the list of all land claims
    //if you need to make changes, use provided methods like .deleteClaim() and .createClaim().
    //this will ensure primary memory (RAM) and secondary memory (disk, database) stay in sync
    public Collection<Claim> getClaims()
    {
        return Collections.unmodifiableCollection(this.claims);
    }

    public Collection<Claim> getClaims(int chunkx, int chunkz)
    {
        ArrayList<Claim> chunkClaims = this.chunksToClaimsMap.get(getChunkHash(chunkx, chunkz));
        if (chunkClaims != null)
        {
            return Collections.unmodifiableCollection(chunkClaims);
        }
        else
        {
            return Collections.unmodifiableCollection(new ArrayList<>());
        }
    }

    //gets an almost-unique, persistent identifier for a chunk
    public static Long getChunkHash(long chunkx, long chunkz)
    {
        return (chunkz ^ (chunkx << 32));
    }

    //gets an almost-unique, persistent identifier for a chunk
    public static Long getChunkHash(Location location)
    {
        return getChunkHash(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public static ArrayList<Long> getChunkHashes(Claim claim) {
        return getChunkHashes(claim.getLesserBoundaryCorner(), claim.getGreaterBoundaryCorner());
    }

    public static ArrayList<Long> getChunkHashes(Location min, Location max) {
        ArrayList<Long> hashes = new ArrayList<>();
        int smallX = min.getBlockX() >> 4;
        int smallZ = min.getBlockZ() >> 4;
        int largeX = max.getBlockX() >> 4;
        int largeZ = max.getBlockZ() >> 4;

        for (int x = smallX; x <= largeX; x++)
        {
            for (int z = smallZ; z <= largeZ; z++)
            {
                hashes.add(getChunkHash(x, z));
            }
        }

        return hashes;
    }

    /*
     * Creates a claim and flags it as being new....throwing a create claim event;
     */
    synchronized public CreateClaimResult createClaim(World world, int x1, int x2, int y1, int y2, int z1, int z2, UUID ownerID, Claim parent, Long id, Player creatingPlayer)
    {
        return createClaim(world, x1, x2, y1, y2, z1, z2, ownerID, parent, id, creatingPlayer, false, true);
    }

    synchronized public CreateClaimResult createClaim(World world, int x1, int x2, int y1, int y2, int z1, int z2, UUID ownerID, Claim parent, Long id, Player creatingPlayer, boolean dryRun)
    {
        return createClaim(world, x1, x2, y1, y2, z1, z2, ownerID, parent, id, creatingPlayer, dryRun, true);
    }

    //creates a claim.
    //if the new claim would overlap an existing claim, returns a failure along with a reference to the existing claim
    //if the new claim would overlap a WorldGuard region where the player doesn't have permission to build, returns a failure with NULL for claim
    //otherwise, returns a success along with a reference to the new claim
    //use ownerName == "" for administrative claims
    //for top level claims, pass parent == NULL
    //DOES adjust claim blocks available on success (players can go into negative quantity available)
    //DOES check for world guard regions where the player doesn't have permission
    //does NOT check a player has permission to create a claim, or enough claim blocks.
    //does NOT check minimum claim size constraints
    //does NOT visualize the new claim for any players
    synchronized public CreateClaimResult createClaim(World world, int x1, int x2, int y1, int y2, int z1, int z2, UUID ownerID, Claim parent, Long id, Player creatingPlayer, boolean dryRun, boolean chargeCreationCost)
    {
        CreateClaimResult result = new CreateClaimResult();

        int smallx, bigx, smally, bigy, smallz, bigz;

        int worldMinY = world.getMinHeight();
        y1 = Math.max(worldMinY, Math.max(GriefPrevention.instance.config_claims_maxDepth, y1));
        y2 = Math.max(worldMinY, Math.max(GriefPrevention.instance.config_claims_maxDepth, y2));

        //determine small versus big inputs
        if (x1 < x2)
        {
            smallx = x1;
            bigx = x2;
        }
        else
        {
            smallx = x2;
            bigx = x1;
        }

        if (y1 < y2)
        {
            smally = y1;
            bigy = y2;
        }
        else
        {
            smally = y2;
            bigy = y1;
        }

        if (z1 < z2)
        {
            smallz = z1;
            bigz = z2;
        }
        else
        {
            smallz = z2;
            bigz = z1;
        }

        if (parent != null)
        {
            Location lesser = parent.getLesserBoundaryCorner();
            Location greater = parent.getGreaterBoundaryCorner();
            if (smallx < lesser.getX() || smallz < lesser.getZ() || bigx > greater.getX() || bigz > greater.getZ())
            {
                result.succeeded = false;
                result.claim = parent;
                return result;
            }
            smally = sanitizeClaimDepth(parent, smally);
        }

        //claims can't be made outside the world border
        final Location smallerBoundaryCorner = new Location(world, smallx, smally, smallz);
        final Location greaterBoundaryCorner = new Location(world, bigx, bigy, bigz);
        if(!world.getWorldBorder().isInside(smallerBoundaryCorner) || !world.getWorldBorder().isInside(greaterBoundaryCorner)){
            result.succeeded = false;
            return result;
        }


        //creative mode claims always go to bedrock
        if (GriefPrevention.instance.config_claims_worldModes.get(world) == ClaimsMode.Creative)
        {
            smallerBoundaryCorner.setY(world.getMinHeight());
        }

        //create a new claim instance (but don't save it, yet)
        Claim newClaim = new Claim(
                smallerBoundaryCorner,
                greaterBoundaryCorner,
                ownerID,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                id);

        newClaim.parent = parent;

        //ensure this new claim won't overlap any existing claims
        ArrayList<Claim> claimsToCheck;
        if (newClaim.parent != null)
        {
            claimsToCheck = newClaim.parent.children;
        }
        else
        {
            claimsToCheck = this.claims;
        }

        for (Claim otherClaim : claimsToCheck)
        {
            //if we find an existing claim which will be overlapped
            if (otherClaim.id != newClaim.id && otherClaim.inDataStore && otherClaim.overlaps(newClaim))
            {
                //result = fail, return conflicting claim
                result.succeeded = false;
                result.claim = otherClaim;
                return result;
            }
        }

        //if worldguard is installed, also prevent claims from overlapping any worldguard regions
        if (GriefPrevention.instance.config_claims_respectWorldGuard && this.worldGuard != null && creatingPlayer != null)
        {
            if (!this.worldGuard.canBuild(newClaim.lesserBoundaryCorner, newClaim.greaterBoundaryCorner, creatingPlayer))
            {
                result.succeeded = false;
                result.claim = null;
                return result;
            }
        }
        if (dryRun)
        {
            // since this is a dry run, just return the unsaved claim as is.
            result.succeeded = true;
            result.claim = newClaim;
            return result;
        }
        assignClaimID(newClaim); // assign a claim ID before calling event, in case a plugin wants to know the ID.
        ClaimCreatedEvent event = new ClaimCreatedEvent(newClaim, creatingPlayer);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
        {
            result.succeeded = false;
            result.claim = null;
            return result;

        }

        if (chargeCreationCost && parent == null && ownerID != null && creatingPlayer != null
                && !GriefPrevention.instance.chargeClaimCreationCost(creatingPlayer, newClaim.getArea(), result))
        {
            result.succeeded = false;
            result.claim = null;
            return result;
        }

        //otherwise add this new claim to the data store to make it effective
        this.addClaim(newClaim, true);

        //then return success along with reference to new claim
        result.succeeded = true;
        result.claim = newClaim;
        return result;
    }

    //saves changes to player data to secondary storage.  MUST be called after you're done making changes, otherwise a reload will lose them
    public void savePlayerDataSync(UUID playerID, PlayerData playerData)
    {
        //ensure player data is already read from file before trying to save
        playerData.getAccruedClaimBlocks();
        playerData.getClaims();

        this.asyncSavePlayerData(playerID, playerData);
    }

    //saves changes to player data to secondary storage.  MUST be called after you're done making changes, otherwise a reload will lose them
    public void savePlayerData(UUID playerID, PlayerData playerData)
    {
        new SavePlayerDataThread(playerID, playerData).start();
    }

    public void asyncSavePlayerData(UUID playerID, PlayerData playerData)
    {
        //save everything except the ignore list
        this.overrideSavePlayerData(playerID, playerData);

        //save the ignore list
        if (playerData.ignoreListChanged)
        {
            StringBuilder fileContent = new StringBuilder();
            try
            {
                for (UUID uuidKey : playerData.ignoredPlayers.keySet())
                {
                    Boolean value = playerData.ignoredPlayers.get(uuidKey);
                    if (value == null) continue;

                    //admin-enforced ignores begin with an asterisk
                    if (value)
                    {
                        fileContent.append("*");
                    }

                    fileContent.append(uuidKey);
                    fileContent.append("\n");
                }

                //write data to file
                File playerDataFile = new File(playerDataFolderPath + File.separator + playerID + ".ignore");
                Files.write(fileContent.toString().trim().getBytes("UTF-8"), playerDataFile);
            }

            //if any problem, log it
            catch (Exception e)
            {
                GriefPrevention.AddLogEntry("GriefPrevention: Unexpected exception saving data for player \"" + playerID.toString() + "\": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    abstract void overrideSavePlayerData(UUID playerID, PlayerData playerData);

    //extends a claim to a new depth
    //respects the max depth config variable
    synchronized public void extendClaim(Claim claim, int newDepth)
    {
        if (claim.parent != null) claim = claim.parent;

        newDepth = sanitizeClaimDepth(claim, newDepth);

        //call event and return if event got cancelled
        ClaimExtendEvent event = new ClaimExtendEvent(claim, newDepth);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        //adjust to new depth
        setNewDepth(claim, event.getNewDepth());
    }

    /**
     * Helper method for sanitizing claim depth to find the minimum expected value.
     *
     * @param claim the claim
     * @param newDepth the new depth
     * @return the sanitized new depth
     */
    private int sanitizeClaimDepth(Claim claim, int newDepth) {
        if (claim.parent != null) claim = claim.parent;

        // Get the old depth including the depth of the lowest subdivision.
        int oldDepth = Math.min(
                claim.getLesserBoundaryCorner().getBlockY(),
                claim.children.stream().mapToInt(child -> child.getLesserBoundaryCorner().getBlockY())
                        .min().orElse(Integer.MAX_VALUE));

        // Use the lowest of the old and new depths.
        newDepth = Math.min(newDepth, oldDepth);
        // Cap depth to maximum depth allowed by the configuration.
        newDepth = Math.max(newDepth, GriefPrevention.instance.config_claims_maxDepth);
        // Cap the depth to the world's minimum height.
        World world = Objects.requireNonNull(claim.getLesserBoundaryCorner().getWorld());
        newDepth = Math.max(newDepth, world.getMinHeight());

        return newDepth;
    }

    /**
     * Helper method for sanitizing and setting claim depth. Saves affected claims.
     *
     * @param claim the claim
     * @param newDepth the new depth
     */
    private void setNewDepth(Claim claim, int newDepth) {
        if (claim.parent != null) claim = claim.parent;

        final int depth = sanitizeClaimDepth(claim, newDepth);

        Stream.concat(Stream.of(claim), claim.children.stream()).forEach(localClaim -> {
            localClaim.lesserBoundaryCorner.setY(depth);
            localClaim.greaterBoundaryCorner.setY(Math.max(localClaim.greaterBoundaryCorner.getBlockY(), depth));
            this.saveClaim(localClaim);
        });
    }

    //starts a siege on a claim
    //does NOT check siege cooldowns, see onCooldown() below
    synchronized public void startSiege(Player attacker, Player defender, Claim defenderClaim)
    {
        //fill-in the necessary SiegeData instance
        SiegeData siegeData = new SiegeData(attacker, defender, defenderClaim);
        PlayerData attackerData = this.getPlayerData(attacker.getUniqueId());
        PlayerData defenderData = this.getPlayerData(defender.getUniqueId());
        attackerData.siegeData = siegeData;
        defenderData.siegeData = siegeData;
        defenderClaim.siegeData = siegeData;

        //start a task to monitor the siege
        //why isn't this a "repeating" task?
        //because depending on the status of the siege at the time the task runs, there may or may not be a reason to run the task again
        SiegeCheckupTask task = new SiegeCheckupTask(siegeData);
        siegeData.checkupTaskID = GriefPrevention.instance.getServer().getScheduler().scheduleSyncDelayedTask(GriefPrevention.instance, task, 20L * 30);
    }

    //ends a siege
    //either winnerName or loserName can be null, but not both
    synchronized public void endSiege(SiegeData siegeData, String winnerName, String loserName, List<ItemStack> drops)
    {
        boolean grantAccess = false;

        //determine winner and loser
        if (winnerName == null && loserName != null)
        {
            if (siegeData.attacker.getName().equals(loserName))
            {
                winnerName = siegeData.defender.getName();
            }
            else
            {
                winnerName = siegeData.attacker.getName();
            }
        }
        else if (winnerName != null && loserName == null)
        {
            if (siegeData.attacker.getName().equals(winnerName))
            {
                loserName = siegeData.defender.getName();
            }
            else
            {
                loserName = siegeData.attacker.getName();
            }
        }

        //if the attacker won, plan to open the doors for looting
        if (siegeData.attacker.getName().equals(winnerName))
        {
            grantAccess = true;
        }

        PlayerData attackerData = this.getPlayerData(siegeData.attacker.getUniqueId());
        attackerData.siegeData = null;

        PlayerData defenderData = this.getPlayerData(siegeData.defender.getUniqueId());
        defenderData.siegeData = null;
        defenderData.lastSiegeEndTimeStamp = System.currentTimeMillis();

        //start a cooldown for this attacker/defender pair
        Long now = Calendar.getInstance().getTimeInMillis();
        Long cooldownEnd = now + 1000 * 60 * GriefPrevention.instance.config_siege_cooldownEndInMinutes;  //one hour from now
        this.siegeCooldownRemaining.put(siegeData.attacker.getName() + "_" + siegeData.defender.getName(), cooldownEnd);

        //start cooldowns for every attacker/involved claim pair
        for (int i = 0; i < siegeData.claims.size(); i++)
        {
            Claim claim = siegeData.claims.get(i);
            claim.siegeData = null;
            this.siegeCooldownRemaining.put(siegeData.attacker.getName() + "_" + claim.getOwnerName(), cooldownEnd);

            //if doors should be opened for looting, do that now
            if (grantAccess)
            {
                claim.doorsOpen = true;
            }
        }

        //cancel the siege checkup task
        GriefPrevention.instance.getServer().getScheduler().cancelTask(siegeData.checkupTaskID);

        //notify everyone who won and lost
        if (winnerName != null && loserName != null)
        {
            GriefPrevention.instance.getServer().broadcastMessage(winnerName + " 在围攻战中击败了 " + loserName + "！");
        }

        //if the claim should be opened to looting
        if (grantAccess)
        {

            Player winner = GriefPrevention.instance.getServer().getPlayer(winnerName);
            if (winner != null)
            {
                //notify the winner
                GriefPrevention.sendMessage(winner, TextMode.Success, Messages.SiegeWinDoorsOpen);

                //schedule a task to secure the claims in about 5 minutes
                SecureClaimTask task = new SecureClaimTask(siegeData);

                GriefPrevention.instance.getServer().getScheduler().scheduleSyncDelayedTask(
                        GriefPrevention.instance, task, 20L * GriefPrevention.instance.config_siege_doorsOpenSeconds
                );
            }
        }

        //if the siege ended due to death, transfer inventory to winner
        if (drops != null)
        {

            Player winner = GriefPrevention.instance.getServer().getPlayer(winnerName);

            Player loser = GriefPrevention.instance.getServer().getPlayer(loserName);
            if (winner != null && loser != null)
            {
                //try to add any drops to the winner's inventory
                for (ItemStack stack : drops)
                {
                    if (stack == null || stack.getType() == Material.AIR || stack.getAmount() == 0) continue;

                    HashMap<Integer, ItemStack> wontFitItems = winner.getInventory().addItem(stack);

                    //drop any remainder on the ground at his feet
                    Object[] keys = wontFitItems.keySet().toArray();
                    Location winnerLocation = winner.getLocation();
                    for (Map.Entry<Integer, ItemStack> wontFitItem : wontFitItems.entrySet())
                    {
                        winner.getWorld().dropItemNaturally(winnerLocation, wontFitItem.getValue());
                    }
                }

                drops.clear();
            }
        }
    }

    //timestamp for each siege cooldown to end
    private final HashMap<String, Long> siegeCooldownRemaining = new HashMap<>();

    //whether or not a sieger can siege a particular victim or claim, considering only cooldowns
    synchronized public boolean onCooldown(Player attacker, Player defender, Claim defenderClaim)
    {
        Long cooldownEnd = null;

        //look for an attacker/defender cooldown
        if (this.siegeCooldownRemaining.get(attacker.getName() + "_" + defender.getName()) != null)
        {
            cooldownEnd = this.siegeCooldownRemaining.get(attacker.getName() + "_" + defender.getName());

            if (Calendar.getInstance().getTimeInMillis() < cooldownEnd)
            {
                return true;
            }

            //if found but expired, remove it
            this.siegeCooldownRemaining.remove(attacker.getName() + "_" + defender.getName());
        }

        //look for genderal defender cooldown
        PlayerData defenderData = this.getPlayerData(defender.getUniqueId());
        if (defenderData.lastSiegeEndTimeStamp > 0)
        {
            long now = System.currentTimeMillis();
            if (now - defenderData.lastSiegeEndTimeStamp > 1000 * 60 * 15) //15 minutes in milliseconds
            {
                return true;
            }
        }

        //look for an attacker/claim cooldown
        if (cooldownEnd == null && this.siegeCooldownRemaining.get(attacker.getName() + "_" + defenderClaim.getOwnerName()) != null)
        {
            cooldownEnd = this.siegeCooldownRemaining.get(attacker.getName() + "_" + defenderClaim.getOwnerName());

            if (Calendar.getInstance().getTimeInMillis() < cooldownEnd)
            {
                return true;
            }

            //if found but expired, remove it
            this.siegeCooldownRemaining.remove(attacker.getName() + "_" + defenderClaim.getOwnerName());
        }

        return false;
    }

    //extend a siege, if it's possible to do so
    synchronized void tryExtendSiege(Player player, Claim claim)
    {
        PlayerData playerData = this.getPlayerData(player.getUniqueId());

        //player must be sieged
        if (playerData.siegeData == null) return;

        //claim isn't already under the same siege
        if (playerData.siegeData.claims.contains(claim)) return;

        //admin claims can't be sieged
        if (claim.isAdminClaim()) return;

        //player must have some level of permission to be sieged in a claim
        Claim currentClaim = claim;
        while (!currentClaim.hasExplicitPermission(player, ClaimPermission.Access))
        {
            if (currentClaim.parent == null) return;
            currentClaim = currentClaim.parent;
        }

        //otherwise extend the siege
        playerData.siegeData.claims.add(claim);
        claim.siegeData = playerData.siegeData;
    }

    //deletes all claims owned by a player
    synchronized public void deleteClaimsForPlayer(UUID playerID, boolean releasePets)
    {
        //make a list of the player's claims
        ArrayList<Claim> claimsToDelete = new ArrayList<>();
        for (Claim claim : this.claims)
        {
            if ((playerID == claim.ownerID || (playerID != null && playerID.equals(claim.ownerID))))
                claimsToDelete.add(claim);
        }

        //delete them one by one
        for (Claim claim : claimsToDelete)
        {
            claim.removeSurfaceFluids(null);

            this.deleteClaim(claim, releasePets);

            //if in a creative mode world, delete the claim
            if (GriefPrevention.instance.creativeRulesApply(claim.getLesserBoundaryCorner()))
            {
                GriefPrevention.instance.restoreClaim(claim, 0);
            }
        }
    }

    //tries to resize a claim
    //see CreateClaim() for details on return value
    synchronized public CreateClaimResult resizeClaim(Claim claim, int newx1, int newx2, int newy1, int newy2, int newz1, int newz2, Player resizingPlayer)
    {
        //try to create this new claim, ignoring the original when checking for overlap
        CreateClaimResult result = this.createClaim(claim.getLesserBoundaryCorner().getWorld(), newx1, newx2, newy1, newy2, newz1, newz2, claim.ownerID, claim.parent, claim.id, resizingPlayer, true);

        //if succeeded
        if (result.succeeded)
        {
            if (claim.parent == null && claim.ownerID != null && resizingPlayer != null
                    && claim.ownerID.equals(resizingPlayer.getUniqueId()))
            {
                long addedArea = (long) result.claim.getArea() - claim.getArea();
                if (addedArea > 0 && !GriefPrevention.instance.chargeClaimCreationCost(resizingPlayer, addedArea, result))
                {
                    result.succeeded = false;
                    result.claim = null;
                    return result;
                }
            }

            removeFromChunkClaimMap(claim); // remove the old boundary from the chunk cache
            // copy the boundary from the claim created in the dry run of createClaim() to our existing claim
            claim.lesserBoundaryCorner = result.claim.lesserBoundaryCorner;
            claim.greaterBoundaryCorner = result.claim.greaterBoundaryCorner;
            // Sanitize claim depth, expanding parent down to the lowest subdivision and subdivisions down to parent.
            // Also saves affected claims.
            setNewDepth(claim, claim.getLesserBoundaryCorner().getBlockY());
            result.claim = claim;
            addToChunkClaimMap(claim); // add the new boundary to the chunk cache
        }

        return result;
    }

    void resizeClaimWithChecks(Player player, PlayerData playerData, int newx1, int newx2, int newy1, int newy2, int newz1, int newz2)
    {
        //for top level claims, apply size rules and claim blocks requirement
        if (playerData.claimResizing.parent == null)
        {
            //measure new claim, apply size rules
            int newWidth;
            int newHeight;
            try
            {
                newWidth = Math.abs(Math.subtractExact(newx1, newx2)) + 1;
                newHeight = Math.abs(Math.subtractExact(newz1, newz2)) + 1;
            }
            catch (ArithmeticException e)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeNeedMoreBlocks, String.valueOf(Integer.MAX_VALUE));
                return;
            }

            boolean smaller = newWidth < playerData.claimResizing.getWidth() || newHeight < playerData.claimResizing.getHeight();

            if (!player.hasPermission("griefprevention.adminclaims") && !playerData.claimResizing.isAdminClaim() && smaller)
            {
                if (newWidth < GriefPrevention.instance.config_claims_minWidth || newHeight < GriefPrevention.instance.config_claims_minWidth)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeClaimTooNarrow, String.valueOf(GriefPrevention.instance.config_claims_minWidth));
                    return;
                }

                int newArea;
                try
                {
                    newArea = Math.multiplyExact(newWidth, newHeight);
                }
                catch (ArithmeticException e)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeNeedMoreBlocks, String.valueOf(Integer.MAX_VALUE));
                    return;
                }
                if (newArea < GriefPrevention.instance.config_claims_minArea)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeClaimInsufficientArea, String.valueOf(GriefPrevention.instance.config_claims_minArea));
                    return;
                }
            }

            //make sure player has enough blocks to make up the difference
            if (!playerData.claimResizing.isAdminClaim() && player.getName().equals(playerData.claimResizing.getOwnerName()))
            {
                int newArea;
                int blocksRemainingAfter;
                try
                {
                    newArea = Math.multiplyExact(newWidth, newHeight);
                    blocksRemainingAfter = playerData.getRemainingClaimBlocks() + (playerData.claimResizing.getArea() - newArea);
                }
                catch (ArithmeticException e)
                {
                    blocksRemainingAfter = Integer.MIN_VALUE + 1;
                }

                if (blocksRemainingAfter < 0)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeNeedMoreBlocks, String.valueOf(Math.abs(blocksRemainingAfter)));
                    this.tryAdvertiseAdminAlternatives(player);
                    return;
                }
            }
        }

        Claim oldClaim = playerData.claimResizing;
        Claim newClaim = new Claim(oldClaim);
        World world = newClaim.getLesserBoundaryCorner().getWorld();
        newClaim.lesserBoundaryCorner = new Location(world, newx1, newy1, newz1);
        newClaim.greaterBoundaryCorner = new Location(world, newx2, newy2, newz2);

        //call event here to check if it has been cancelled
        ClaimResizeEvent event = new ClaimModifiedEvent(oldClaim, newClaim, player); // Swap to ClaimResizeEvent when ClaimModifiedEvent is removed
        Bukkit.getPluginManager().callEvent(event);

        //return here if event is cancelled
        if (event.isCancelled()) return;

        //special rule for making a top-level claim smaller.  to check this, verifying the old claim's corners are inside the new claim's boundaries.
        //rule: in any mode, shrinking a claim removes any surface fluids
        boolean smaller = false;
        if (oldClaim.parent == null)
        {
            //if the new claim is smaller
            if (!newClaim.contains(oldClaim.getLesserBoundaryCorner(), true, false) || !newClaim.contains(oldClaim.getGreaterBoundaryCorner(), true, false))
            {
                smaller = true;

                //remove surface fluids about to be unclaimed
                oldClaim.removeSurfaceFluids(newClaim);
            }
        }

        //ask the datastore to try and resize the claim, this checks for conflicts with other claims
        CreateClaimResult result = GriefPrevention.instance.dataStore.resizeClaim(
                playerData.claimResizing,
                newClaim.getLesserBoundaryCorner().getBlockX(),
                newClaim.getGreaterBoundaryCorner().getBlockX(),
                newClaim.getLesserBoundaryCorner().getBlockY(),
                newClaim.getGreaterBoundaryCorner().getBlockY(),
                newClaim.getLesserBoundaryCorner().getBlockZ(),
                newClaim.getGreaterBoundaryCorner().getBlockZ(),
                player);

        if (result.succeeded && result.claim != null)
        {
            //decide how many claim blocks are available for more resizing
            int claimBlocksRemaining = 0;
            if (!playerData.claimResizing.isAdminClaim())
            {
                UUID ownerID = playerData.claimResizing.ownerID;
                if (playerData.claimResizing.parent != null)
                {
                    ownerID = playerData.claimResizing.parent.ownerID;
                }
                if (ownerID == player.getUniqueId())
                {
                    claimBlocksRemaining = playerData.getRemainingClaimBlocks();
                }
                else
                {
                    PlayerData ownerData = this.getPlayerData(ownerID);
                    claimBlocksRemaining = ownerData.getRemainingClaimBlocks();
                    OfflinePlayer owner = GriefPrevention.instance.getServer().getOfflinePlayer(ownerID);
                    if (!owner.isOnline())
                    {
                        this.clearCachedPlayerData(ownerID);
                    }
                }
            }

            //inform about success, visualize, communicate remaining blocks available
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.ClaimResizeSuccess, String.valueOf(claimBlocksRemaining));
            GriefPrevention.sendClaimEconomyCostPaid(player, result);
            BoundaryVisualization.visualizeClaim(player, result.claim, VisualizationType.CLAIM);

            //if resizing someone else's claim, make a log entry
            if (!player.getUniqueId().equals(playerData.claimResizing.ownerID) && playerData.claimResizing.parent == null)
            {
                GriefPrevention.AddLogEntry(player.getName() + " resized " + playerData.claimResizing.getOwnerName() + "'s claim at " + GriefPrevention.getfriendlyLocationString(playerData.claimResizing.lesserBoundaryCorner) + ".");
            }

            //if increased to a sufficiently large size and no subdivisions yet, send subdivision instructions
            if (oldClaim.getArea() < 1000 && result.claim.getArea() >= 1000 && result.claim.children.size() == 0 && !player.hasPermission("griefprevention.adminclaims"))
            {
                GriefPrevention.sendMessage(player, TextMode.Info, Messages.BecomeMayor, 200L);
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionVideo2, 201L, DataStore.SUBDIVISION_VIDEO_URL);
            }

            //if in a creative mode world and shrinking an existing claim, restore any unclaimed area
            if (smaller && GriefPrevention.instance.creativeRulesApply(oldClaim.getLesserBoundaryCorner()))
            {
                GriefPrevention.sendMessage(player, TextMode.Warn, Messages.UnclaimCleanupWarning);
                GriefPrevention.instance.restoreClaim(oldClaim, 20L * 60 * 2);  //2 minutes
                GriefPrevention.AddLogEntry(player.getName() + " shrank a claim @ " + GriefPrevention.getfriendlyLocationString(playerData.claimResizing.getLesserBoundaryCorner()));
            }

            //clean up
            playerData.claimResizing = null;
            playerData.lastShovelLocation = null;
        }
        else
        {
            if (!GriefPrevention.sendClaimEconomyFailure(player, result))
            {
                if (result.claim != null)
                {
                    //inform player
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlap);

                    //show the player the conflicting claim
                    BoundaryVisualization.visualizeClaim(player, result.claim, VisualizationType.CONFLICT_ZONE);

                    //log conflicting claim, if debug logs are enabled
                    GriefPrevention.AddLogEntry(player.getName() + " attempted to create a claim, but conflicted with claim " + result.claim.id, CustomLogEntryTypes.Debug);
                }
                else
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlapRegion);
                }
            }
        }
    }

    //educates a player about /adminclaims and /acb, if he can use them 
    void tryAdvertiseAdminAlternatives(Player player)
    {
        if (player.hasPermission("griefprevention.adminclaims") && player.hasPermission("griefprevention.adjustclaimblocks"))
        {
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.AdvertiseACandACB);
        }
        else if (player.hasPermission("griefprevention.adminclaims"))
        {
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.AdvertiseAdminClaims);
        }
        else if (player.hasPermission("griefprevention.adjustclaimblocks"))
        {
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.AdvertiseACB);
        }
    }

    protected void loadMessages()
    {
        Messages[] messageIDs = Messages.values();
        this.messages = new String[Messages.values().length];

        HashMap<String, CustomizableMessage> defaults = new HashMap<>();

        //initialize defaults
        this.addDefault(defaults, Messages.RespectingClaims, "现在已启用领地保护。", null);
        this.addDefault(defaults, Messages.IgnoringClaims, "现在已忽略领地保护。", null);
        this.addDefault(defaults, Messages.NoCreativeUnClaim, "无法放弃此区域。你只能扩大此领地或创建其他领地。", null);
        this.addDefault(defaults, Messages.SuccessfulAbandon, "已放弃所有领地。你现在还有 {0} 个可用领地格。", "0: remaining blocks");
        this.addDefault(defaults, Messages.RestoreNatureActivate, "自然恢复模式已就绪！右键即可恢复自然地形，输入 /basicclaims 退出。", null);
        this.addDefault(defaults, Messages.RestoreNatureAggressiveActivate, "强力恢复模式已启用。请勿在你想保留的建筑下方使用！右键强力恢复地形，输入 /basicclaims 退出。", null);
        this.addDefault(defaults, Messages.FillModeActive, "填充模式已启用，半径为 {0}。右键要填充的区域。", "0: fill radius");
        this.addDefault(defaults, Messages.TransferClaimPermission, "此命令需要管理员领地权限。", null);
        this.addDefault(defaults, Messages.TransferClaimMissing, "此处没有领地。请站在要转让的管理员领地内。", null);
        this.addDefault(defaults, Messages.TransferClaimAdminOnly, "只能将管理员领地转让给玩家。", null);
        this.addDefault(defaults, Messages.PlayerNotFound2, "近期没有玩家以该名称登录过。", null);
        this.addDefault(defaults, Messages.TransferTopLevel, "只能转让顶级领地，不能转让子领地。请站到子领地外重试。", null);
        this.addDefault(defaults, Messages.TransferSuccess, "领地已转让。", null);
        this.addDefault(defaults, Messages.TrustListNoClaim, "请站在要查看权限的领地内。", null);
        this.addDefault(defaults, Messages.ClearPermsOwnerOnly, "只有领地所有者才能清除全部权限。", null);
        this.addDefault(defaults, Messages.UntrustIndividualAllClaims, "已撤销 {0} 在你所有领地中的权限。若要设置单个领地的权限，请站进该领地。", "0: untrusted player");
        this.addDefault(defaults, Messages.UntrustEveryoneAllClaims, "已清除你所有领地的权限。若要设置单个领地权限，请站在该领地内。", null);
        this.addDefault(defaults, Messages.NoPermissionTrust, "你没有 {0} 的权限，无法在此管理权限。", "0: claim owner's name");
        this.addDefault(defaults, Messages.ClearPermissionsOneClaim, "已清除此领地的权限。若要设置所有领地的权限，请站在领地外。", null);
        this.addDefault(defaults, Messages.UntrustIndividualSingleClaim, "已撤销 {0} 在此领地中的权限。若要设置所有领地权限，请站在领地外。", "0: untrusted player");
        this.addDefault(defaults, Messages.OnlySellBlocks, "领地格只能出售，不能购买。", null);
        this.addDefault(defaults, Messages.BlockPurchaseCost, "每个领地格售价为 {0}。你的余额为 {1}。", "0: cost of one block; 1: player's account balance");
        this.addDefault(defaults, Messages.ClaimBlockLimit, "你已达到领地格上限，无法继续购买。", null);
        this.addDefault(defaults, Messages.InsufficientFunds, "余额不足。你还需要 {0}，当前余额为 {1}。", "0: total cost; 1: player's account balance");
        this.addDefault(defaults, Messages.ClaimCostPaid, "本次领地操作已扣除 {0} 金币。", "0: economy-formatted claim cost");
        this.addDefault(defaults, Messages.ClaimEconomyUnavailable, "领地经济服务暂不可用，本次操作未完成，请联系管理员。", null);
        this.addDefault(defaults, Messages.ClaimEconomyTransactionFailed, "金币扣款失败，本次操作未完成，请稍后重试或联系管理员。", null);
        this.addDefault(defaults, Messages.MaxBonusReached, "无法再购买 {0} 个领地格。服务器额外领地格上限为 {1}。", "0: block count; 1: bonus claims limit");
        this.addDefault(defaults, Messages.PurchaseConfirmation, "已从你的账户扣除 {0}。你现在有 {1} 个可用领地格。", "0: total cost; 1: remaining blocks");
        this.addDefault(defaults, Messages.OnlyPurchaseBlocks, "领地格只能购买，不能出售。", null);
        this.addDefault(defaults, Messages.BlockSaleValue, "每个领地格可售 {0}。你有 {1} 个领地格可出售。", "0: block value; 1: available blocks");
        this.addDefault(defaults, Messages.NotEnoughBlocksForSale, "你没有这么多可出售的领地格。", null);
        this.addDefault(defaults, Messages.BlockSaleConfirmation, "已向你的账户存入 {0}。你现在有 {1} 个可用领地格。", "0: amount deposited; 1: remaining blocks");
        this.addDefault(defaults, Messages.AdminClaimsMode, "管理员领地模式已启用。新建的领地免费，且其他管理员也可编辑。", null);
        this.addDefault(defaults, Messages.BasicClaimsMode, "已切回普通领地创建模式。", null);
        this.addDefault(defaults, Messages.SubdivisionMode, "已进入子领地模式。使用金铲在现有领地中创建子领地，输入 /basicclaims 退出。", null);
        this.addDefault(defaults, Messages.SubdivisionVideo2, "点击查看子领地帮助：{0}", "0:video URL");
        this.addDefault(defaults, Messages.DeleteClaimMissing, "此处没有领地。", null);
        this.addDefault(defaults, Messages.DeletionSubdivisionWarning, "此领地包含子领地。如确定要删除，请再次执行 /deleteclaim。", null);
        this.addDefault(defaults, Messages.DeleteSuccess, "领地已删除。", null);
        this.addDefault(defaults, Messages.CantDeleteAdminClaim, "你没有权限删除管理员领地。", null);
        this.addDefault(defaults, Messages.DeleteAllSuccess, "已删除 {0} 的所有领地。", "0: owner's name");
        this.addDefault(defaults, Messages.NoDeletePermission, "你没有权限删除领地。", null);
        this.addDefault(defaults, Messages.AllAdminDeleted, "所有管理员领地已删除。", null);
        this.addDefault(defaults, Messages.AdjustBlocksSuccess, "已将 {0} 的额外领地格调整 {1}。额外领地格总数：{2}。", "0: player; 1: adjustment; 2: new total");
        this.addDefault(defaults, Messages.AdjustBlocksAllSuccess, "已将所有在线玩家的额外领地格调整 {0}。", "0: adjustment amount");
        this.addDefault(defaults, Messages.NotTrappedHere, "你可以在这里建造，快自救吧。", null);
        this.addDefault(defaults, Messages.RescuePending, "保持不动 10 秒即可传送离开，请稍候。", null);
        this.addDefault(defaults, Messages.NonSiegeWorld, "此处已禁用攻城。", null);
        this.addDefault(defaults, Messages.AlreadySieging, "你已经参与攻城。", null);
        this.addDefault(defaults, Messages.AlreadyUnderSiegePlayer, "{0} 已处于被攻城状态，加入战斗吧！", "0: defending player");
        this.addDefault(defaults, Messages.NotSiegableThere, "{0} 在此处未受保护。", "0: defending player");
        this.addDefault(defaults, Messages.SiegeTooFarAway, "距离太远，无法发起攻城。", null);
        this.addDefault(defaults, Messages.NoSiegeYourself, "不能攻城自己。", null);
        this.addDefault(defaults, Messages.NoSiegeDefenseless, "该玩家没有防备，去找其他对手吧。", null);
        this.addDefault(defaults, Messages.AlreadyUnderSiegeArea, "该区域已经处于攻城状态，加入战斗吧！", null);
        this.addDefault(defaults, Messages.NoSiegeAdminClaim, "此区域已禁用攻城。", null);
        this.addDefault(defaults, Messages.SiegeOnCooldown, "你对这名防守者或该领地仍处于攻城冷却中，请另找目标。", null);
        this.addDefault(defaults, Messages.SiegeAlert, "你正遭到攻城！此时退出游戏会死亡。你必须击败 {0}、等他放弃，或逃离。", "0: attacker name");
        this.addDefault(defaults, Messages.SiegeConfirmed, "攻城开始！此时退出游戏会死亡。你必须击败 {0}、将他赶走，或认输离开。", "0: defender name");
        this.addDefault(defaults, Messages.AbandonClaimMissing, "请站在要删除的领地内，或使用 /abandonallclaims。", null);
        this.addDefault(defaults, Messages.NotYourClaim, "这不是你的领地。", null);
        this.addDefault(defaults, Messages.DeleteTopLevelClaim, "要删除子领地，请站在其中；否则使用 /abandontoplevelclaim 删除该领地及其所有子领地。", null);
        this.addDefault(defaults, Messages.AbandonSuccess, "已放弃领地。你现在有 {0} 个可用领地格。", "0: remaining claim blocks");
        this.addDefault(defaults, Messages.ConfirmAbandonAllClaims, "确定要放弃全部领地吗？请执行 /abandonallclaims confirm 确认。", null);
        this.addDefault(defaults, Messages.CantGrantThatPermission, "你不能授予自己也没有的权限。", null);
        this.addDefault(defaults, Messages.GrantPermissionNoClaim, "请站在要授予权限的领地内。", null);
        this.addDefault(defaults, Messages.GrantPermissionConfirmation, "已向 {0} 授予 {1} 权限（范围：{2}）。", "0: target player; 1: permission description; 2: scope (changed claims)");
        this.addDefault(defaults, Messages.ManageUniversalPermissionsInstruction, "若要管理所有领地的权限，请站在领地外。", null);
        this.addDefault(defaults, Messages.ManageOneClaimPermissionsInstruction, "若要管理指定领地的权限，请站在其中。", null);
        this.addDefault(defaults, Messages.CollectivePublic, "所有玩家", "as in 'granted the public permission to...'");
        this.addDefault(defaults, Messages.BuildPermission, "建造", null);
        this.addDefault(defaults, Messages.ContainersPermission, "使用容器和动物", null);
        this.addDefault(defaults, Messages.AccessPermission, "使用按钮和拉杆", null);
        this.addDefault(defaults, Messages.PermissionsPermission, "管理权限", null);
        this.addDefault(defaults, Messages.LocationCurrentClaim, "在此领地中", null);
        this.addDefault(defaults, Messages.LocationAllClaims, "在你的所有领地中", null);
        this.addDefault(defaults, Messages.PvPImmunityStart, "你的背包为空期间，其他玩家无法攻击你。", null);
        this.addDefault(defaults, Messages.SiegeNoDrop, "攻城期间不能转交物品。", null);
        this.addDefault(defaults, Messages.DonateItemsInstruction, "要转移手中物品，请再次左键点击箱子。", null);
        this.addDefault(defaults, Messages.ChestFull, "箱子已满。", null);
        this.addDefault(defaults, Messages.DonationSuccess, "物品已放入箱子！", null);
        this.addDefault(defaults, Messages.PlayerTooCloseForFire2, "不能在离其他玩家这么近的地方点火。", null);
        this.addDefault(defaults, Messages.TooDeepToClaim, "此箱子埋得太深，无法受到保护。请考虑换个位置。", null);
        this.addDefault(defaults, Messages.ChestClaimConfirmation, "此箱子已受到保护。", null);
        this.addDefault(defaults, Messages.AutomaticClaimNotification, "此箱子及周围方块已受到破坏和盗窃保护。", null);
        this.addDefault(defaults, Messages.AutomaticClaimOtherClaimTooClose, "无法为箱子创建领地：附近已有其他领地！", null);
        this.addDefault(defaults, Messages.UnprotectedChestWarning, "此箱子未受到保护。可用金铲扩展现有领地或创建新领地。", null);
        this.addDefault(defaults, Messages.ThatPlayerPvPImmune, "你不能伤害没有防备的玩家。", null);
        this.addDefault(defaults, Messages.CantFightWhileImmune, "你受到 PvP 保护期间不能攻击其他玩家。", null);
        this.addDefault(defaults, Messages.NoDamageClaimedEntity, "这是 {0} 的。", "0: owner name");
        this.addDefault(defaults, Messages.ShovelBasicClaimMode, "金铲已切回普通领地模式。", null);
        this.addDefault(defaults, Messages.RemainingBlocks, "你还可以圈地 {0} 格。", "0: remaining blocks");
        this.addDefault(defaults, Messages.CreativeBasicsVideo2, "点击查看领地保护帮助：{0}", "{0}: video URL");
        this.addDefault(defaults, Messages.SurvivalBasicsVideo2, "点击查看领地保护帮助：{0}", "{0}: video URL");
        this.addDefault(defaults, Messages.TrappedChatKeyword, "trapped;stuck", "When mentioned in chat, players get information about the /trapped command (multiple words can be separated with semi-colons)");
        this.addDefault(defaults, Messages.TrappedInstructions, "被困在他人领地里了吗？试试 /trapped 命令。", null);
        this.addDefault(defaults, Messages.PvPNoDrop, "PvP 战斗期间不能丢弃物品。", null);
        this.addDefault(defaults, Messages.SiegeNoTeleport, "无法从正在遭受攻城的区域传送出去。", null);
        this.addDefault(defaults, Messages.BesiegedNoTeleport, "无法传送进正在遭受攻城的区域。", null);
        this.addDefault(defaults, Messages.SiegeNoContainers, "攻城期间不能使用容器。", null);
        this.addDefault(defaults, Messages.PvPNoContainers, "PvP 战斗期间不能使用容器。", null);
        this.addDefault(defaults, Messages.PvPImmunityEnd, "你现在可以与其他玩家战斗了。", null);
        this.addDefault(defaults, Messages.NoBedPermission, "{0} 未授予你在此睡觉的权限。", "0: claim owner");
        this.addDefault(defaults, Messages.NoWildernessBuckets, "只能在自己的领地内或地下倾倒液体。", null);
        this.addDefault(defaults, Messages.NoLavaNearOtherPlayer, "不能在离 {0} 这么近的地方倒岩浆。", "0: nearby player");
        this.addDefault(defaults, Messages.TooFarAway, "距离太远。", null);
        this.addDefault(defaults, Messages.BlockNotClaimed, "此方块尚未被圈地。", null);
        this.addDefault(defaults, Messages.BlockClaimed, "该方块属于 {0} 的领地。", "0: claim owner");
        this.addDefault(defaults, Messages.SiegeNoShovel, "攻城期间不能使用金铲工具。", null);
        this.addDefault(defaults, Messages.RestoreNaturePlayerInChunk, "无法恢复：该区块内有玩家 {0}。", "0: nearby player");
        this.addDefault(defaults, Messages.NoCreateClaimPermission, "你没有圈地权限。", null);
        this.addDefault(defaults, Messages.ResizeClaimTooNarrow, "新尺寸过小。领地宽度至少为 {0} 格。", "0: minimum claim width");
        this.addDefault(defaults, Messages.ResizeNeedMoreBlocks, "可用领地格不足。还需要 {0} 格。", "0: how many needed");
        this.addDefault(defaults, Messages.ClaimResizeSuccess, "领地已调整。剩余 {0} 个可用领地格。", "0: remaining blocks");
        this.addDefault(defaults, Messages.ResizeFailOverlap, "无法在此调整领地，因为会与附近的其他领地重叠。", null);
        this.addDefault(defaults, Messages.ResizeStart, "正在调整领地。请在新位置再次使用金铲设置此角。", null);
        this.addDefault(defaults, Messages.ResizeFailOverlapSubdivision, "无法在此创建子领地，因为它会与其他子领地重叠。可使用 /abandonclaim 删除，或用金铲调整角点。", null);
        this.addDefault(defaults, Messages.SubdivisionStart, "已设定子领地的一个角！请在对角位置再次使用金铲。", null);
        this.addDefault(defaults, Messages.CreateSubdivisionOverlap, "所选区域与其他子领地重叠。", null);
        this.addDefault(defaults, Messages.SubdivisionSuccess, "子领地已创建！使用 /trust 与好友共享。", null);
        this.addDefault(defaults, Messages.CreateClaimFailOverlap, "无法在此创建领地，因为它会与自己的其他领地重叠。使用 /abandonclaim 删除，或用金铲调整角点。", null);
        this.addDefault(defaults, Messages.CreateClaimFailOverlapOtherPlayer, "无法在此创建领地，因为所选区域与 {0} 的领地重叠。", "0: other claim owner");
        this.addDefault(defaults, Messages.ClaimsDisabledWorld, "此世界已禁用圈地。", null);
        this.addDefault(defaults, Messages.ClaimStart, "已设定领地的一个角！请在对角位置再次使用金铲圈出矩形区域。收起金铲可取消。", null);
        this.addDefault(defaults, Messages.NewClaimTooNarrow, "领地过小，宽度至少为 {0} 格。", "0: minimum claim width");
        this.addDefault(defaults, Messages.ResizeClaimInsufficientArea, "领地过小，至少需要 {0} 个领地格。", "0: minimum claim area");
        this.addDefault(defaults, Messages.CreateClaimInsufficientBlocks, "可用领地格不足以圈下整个区域。还需要 {0} 格。", "0: additional blocks needed");
        this.addDefault(defaults, Messages.AbandonClaimAdvertisement, "输入 /abandonclaim 删除其他领地以释放领地格。", null);
        this.addDefault(defaults, Messages.CreateClaimFailOverlapShort, "所选区域与现有领地重叠。", null);
        this.addDefault(defaults, Messages.CreateClaimSuccess, "领地已创建！使用 /trust 与好友共享。", null);
        this.addDefault(defaults, Messages.SiegeWinDoorsOpen, "恭喜！按钮和拉杆已暂时解锁。", null);
        this.addDefault(defaults, Messages.RescueAbortedMoved, "你移动了！救援已取消。", null);
        this.addDefault(defaults, Messages.SiegeDoorsLockedEjection, "掠夺时间结束！你已被逐出该领地。", null);
        this.addDefault(defaults, Messages.NoModifyDuringSiege, "领地遭受攻城期间不能修改。", null);
        this.addDefault(defaults, Messages.OnlyOwnersModifyClaims, "只有 {0} 可以修改此领地。", "0: owner name");
        this.addDefault(defaults, Messages.NoBuildUnderSiege, "此领地正在遭受 {0} 攻城，任何人都不能建造。", "0: attacker name");
        this.addDefault(defaults, Messages.NoBuildPvP, "PvP 战斗期间不能在领地内建造。", null);
        this.addDefault(defaults, Messages.NoBuildPermission, "你没有 {0} 授予的建造权限。", "0: owner name");
        this.addDefault(defaults, Messages.NonSiegeMaterial, "该材料过于坚固，无法破坏。", null);
        this.addDefault(defaults, Messages.NoOwnerBuildUnderSiege, "领地遭受攻城期间不能进行修改。", null);
        this.addDefault(defaults, Messages.NoAccessPermission, "你没有 {0} 授予的使用权限。", "0: owner name.  access permission controls buttons, levers, and beds");
        this.addDefault(defaults, Messages.NoContainersSiege, "此领地正在遭受 {0} 攻城，目前无法使用容器。", "0: attacker name");
        this.addDefault(defaults, Messages.NoContainersPermission, "你没有 {0} 授予的使用权限。", "0: owner's name.  containers also include crafting blocks");
        this.addDefault(defaults, Messages.OwnerNameForAdminClaims, "管理员", "as in 'You don't have an administrator's permission to build here.'");
        this.addDefault(defaults, Messages.UnknownPlayerName, "某位玩家", "Name used for unknown players. UUID will be appended if available: \"someone (01234567-0123-0123-0123-0123456789ab)\"");
        this.addDefault(defaults, Messages.ClaimTooSmallForEntities, "该领地太小，无法容纳此实体。请扩大领地。", null);
        this.addDefault(defaults, Messages.TooManyEntitiesInClaim, "该领地内实体过多。请扩大领地，或移除部分动物、怪物、画、矿车。", null);
        this.addDefault(defaults, Messages.YouHaveNoClaims, "你没有任何领地。", null);
        this.addDefault(defaults, Messages.ConfirmFluidRemoval, "放弃此领地将移除领地内的岩浆。确定要继续，请再次输入 /abandonclaim。", null);
        this.addDefault(defaults, Messages.AutoBanNotify, "已自动封禁 {0}（{1}）。详情请查看日志。", null);
        this.addDefault(defaults, Messages.AdjustGroupBlocksSuccess, "已将拥有 {0} 权限的玩家额外领地格调整 {1}。调整后总数：{2}。", "0: permission; 1: adjustment amount; 2: new total bonus");
        this.addDefault(defaults, Messages.InvalidPermissionID, "请填写玩家名称，或使用方括号指定权限节点。", null);
        this.addDefault(defaults, Messages.HowToClaimRegex, "(^|.*\\W)how\\W.*\\W(claim|protect|lock)(\\W.*|$)", "This is a Java Regular Expression.  Look it up before editing!  It's used to tell players about the demo video when they ask how to claim land.");
        this.addDefault(defaults, Messages.NoBuildOutsideClaims, "请先圈地，才能在此建造。", null);
        this.addDefault(defaults, Messages.PlayerOfflineTime, "  最后登录：{0} 天前。", "0: number of full days since last login");
        this.addDefault(defaults, Messages.BuildingOutsideClaims, "此处也允许其他玩家建造。建议先圈地保护建筑！", null);
        this.addDefault(defaults, Messages.TrappedWontWorkHere, "抱歉，未找到安全的传送位置。请联系管理员。", null);
        this.addDefault(defaults, Messages.CommandBannedInPvP, "PvP 战斗期间不能使用该命令。", null);
        this.addDefault(defaults, Messages.UnclaimCleanupWarning, "你放弃的区域可能会被其他玩家修改或被管理员清理。如果有想保留的建筑，请重新圈地。", null);
        this.addDefault(defaults, Messages.BuySellNotConfigured, "抱歉，领地格买卖功能未启用。", null);
        this.addDefault(defaults, Messages.NoTeleportPvPCombat, "与玩家战斗期间不能传送。", null);
        this.addDefault(defaults, Messages.NoTNTDamageAboveSeaLevel, "提示：TNT 不会破坏海平面以上的方块。", null);
        this.addDefault(defaults, Messages.NoTNTDamageClaims, "提示：TNT 不会破坏领地内的方块。", null);
        this.addDefault(defaults, Messages.IgnoreClaimsAdvertisement, "如需强制操作，请输入 /ignoreclaims。", null);
        this.addDefault(defaults, Messages.NoPermissionForCommand, "你没有权限执行此操作。", null);
        this.addDefault(defaults, Messages.ClaimsListNoPermission, "你没有权限查看其他玩家的领地信息。", null);
        this.addDefault(defaults, Messages.ExplosivesDisabled, "此领地已防爆。再次输入 /claimexplosions 可关闭防爆。", null);
        this.addDefault(defaults, Messages.ExplosivesEnabled, "此领地现在会受到爆炸破坏。再次输入 /claimexplosions 可重新启用防护。", null);
        this.addDefault(defaults, Messages.ClaimExplosivesAdvertisement, "若要允许爆炸破坏此领地中的方块，请输入 /claimexplosions。", null);
        this.addDefault(defaults, Messages.PlayerInPvPSafeZone, "该玩家位于 PvP 安全区。", null);
        this.addDefault(defaults, Messages.NoPistonsOutsideClaims, "提示：活塞不能推动领地外的方块。", null);
        this.addDefault(defaults, Messages.SoftMuted, "已对 {0} 启用软禁言。", "0: The changed player's name.");
        this.addDefault(defaults, Messages.UnSoftMuted, "已对 {0} 解除软禁言。", "0: The changed player's name.");
        this.addDefault(defaults, Messages.DropUnlockAdvertisement, "其他玩家无法拾取你的掉落物，除非你先输入 /unlockdrops。", null);
        this.addDefault(defaults, Messages.PickupBlockedExplanation, "只有 {0} 输入 /unlockdrops 后，你才能拾取该物品。", "0: The item stack's owner.");
        this.addDefault(defaults, Messages.DropUnlockConfirmation, "已开放拾取你的掉落物，其他玩家现在可以拾取（直到你再次死亡）。", null);
        this.addDefault(defaults, Messages.DropUnlockOthersConfirmation, "已开放拾取 {0} 的掉落物。", "0: The owner of the unlocked drops.");
        this.addDefault(defaults, Messages.AdvertiseACandACB, "你可以输入 /acb 增加自己的领地格，或输入 /adminclaims 创建免费的管理员领地。", null);
        this.addDefault(defaults, Messages.AdvertiseAdminClaims, "也可以使用 /adminclaims 创建管理员领地，并与其他管理员共享。", null);
        this.addDefault(defaults, Messages.AdvertiseACB, "你可以输入 /acb 增加自己的领地格。", null);
        this.addDefault(defaults, Messages.NotYourPet, "{0} 的宠物尚未通过 /givepet 转让给你。", "0: owner name");
        this.addDefault(defaults, Messages.PetGiveawayConfirmation, "宠物已转让。", null);
        this.addDefault(defaults, Messages.PetTransferCancellation, "宠物转让已取消。", null);
        this.addDefault(defaults, Messages.ReadyToTransferPet, "准备转让宠物！右键点击要赠送的宠物，或输入 /givepet cancel 取消。", null);
        this.addDefault(defaults, Messages.AvoidGriefClaimLand, "预防破坏！圈好领地即可保护你的建筑。", null);
        this.addDefault(defaults, Messages.BecomeMayor, "划分子领地，成为领地主！", null);
        this.addDefault(defaults, Messages.ClaimCreationFailedOverClaimCountLimit, "你已达到领地数量上限。请先输入 /abandonclaim 删除一个领地。", null);
        this.addDefault(defaults, Messages.CreateClaimFailOverlapRegion, "你无权在此建造，因此不能圈下整个区域。", null);
        this.addDefault(defaults, Messages.ResizeFailOverlapRegion, "你没有权限在该区域建造，无法将其圈入领地。", null);
        this.addDefault(defaults, Messages.ShowNearbyClaims, "找到 {0} 个领地。", "0: Number of claims found.");
        this.addDefault(defaults, Messages.NoChatUntilMove, "抱歉，请先移动一段距离再聊天。这里有很多刷屏机器人。:)", null);
        this.addDefault(defaults, Messages.SiegeImmune, "该玩家免疫 /siege 攻城。", null);
        this.addDefault(defaults, Messages.SetClaimBlocksSuccess, "可累积的领地格数量已更新。", null);
        this.addDefault(defaults, Messages.IgnoreConfirmation, "你已屏蔽该玩家的聊天消息。", null);
        this.addDefault(defaults, Messages.UnIgnoreConfirmation, "你已取消屏蔽该玩家的聊天消息。", null);
        this.addDefault(defaults, Messages.NotIgnoringPlayer, "你没有屏蔽该玩家。", null);
        this.addDefault(defaults, Messages.SeparateConfirmation, "这两名玩家现在会互相屏蔽聊天消息。", null);
        this.addDefault(defaults, Messages.UnSeparateConfirmation, "这两名玩家不再互相屏蔽聊天消息。", null);
        this.addDefault(defaults, Messages.NotIgnoringAnyone, "你没有屏蔽任何玩家。", null);
        this.addDefault(defaults, Messages.TrustListHeader, "此处明确设置的权限：", "0: The claim's owner");
        this.addDefault(defaults, Messages.Manage, "管理", null);
        this.addDefault(defaults, Messages.Build, "建造", null);
        this.addDefault(defaults, Messages.Containers, "容器", null);
        this.addDefault(defaults, Messages.Access, "访问", null);
        this.addDefault(defaults, Messages.HasSubclaimRestriction, "此子领地不会继承父领地的权限。", null);
        this.addDefault(defaults, Messages.StartBlockMath, "{0} 游戏获得 + {1} 额外 = 共 {2} 格。", null);
        this.addDefault(defaults, Messages.ClaimsListHeader, "领地：", null);
        this.addDefault(defaults, Messages.ContinueBlockMath, "（-{0} 格）", null);
        this.addDefault(defaults, Messages.EndBlockMath, " = 还剩 {0} 格可用", null);
        this.addDefault(defaults, Messages.NoClaimDuringPvP, "PvP 战斗期间不能圈地。", null);
        this.addDefault(defaults, Messages.UntrustAllOwnerOnly, "只有领地所有者才能清除所有权限。", null);
        this.addDefault(defaults, Messages.ManagersDontUntrustManagers, "只有领地所有者才能撤销管理员权限。", null);
        this.addDefault(defaults, Messages.PlayerNotIgnorable, "你不能屏蔽该玩家。", null);
        this.addDefault(defaults, Messages.NoEnoughBlocksForChestClaim, "你的领地格不足，未能自动创建领地。可使用 /claimslist 查看剩余领地格。", null);
        this.addDefault(defaults, Messages.MustHoldModificationToolForThat, "手持金铲后才能执行此操作。", null);
        this.addDefault(defaults, Messages.StandInClaimToResize, "请站在要调整的领地内。", null);
        this.addDefault(defaults, Messages.ClaimsExtendToSky, "领地会自动延伸至世界最高建造高度。", null);
        this.addDefault(defaults, Messages.ClaimsAutoExtendDownward, "在领地下方放置方块时，领地会自动向下扩展。", null);
        this.addDefault(defaults, Messages.MinimumRadius, "最小半径为 {0}。", "0: minimum radius");
        this.addDefault(defaults, Messages.RadiusRequiresGoldenShovel, "指定半径时必须手持金铲。", null);
        this.addDefault(defaults, Messages.ClaimTooSmallForActiveBlocks, "该领地太小，无法容纳功能方块（漏斗、刷怪笼、信标等）。请先扩大领地。", null);
        this.addDefault(defaults, Messages.TooManyActiveBlocksInClaim, "该领地中的功能方块已达上限。请扩大领地，或先移除其他功能方块。", null);

        this.addDefault(defaults, Messages.BookAuthor, "BigScary", null);
        this.addDefault(defaults, Messages.BookTitle, "圈地指南", null);
        this.addDefault(defaults, Messages.BookLink, "点击查看：{0}", "{0}: video URL");
        this.addDefault(defaults, Messages.BookIntro, "圈地可保护你的建筑和物品！点击上方链接，3 分钟内学会圈地。:)", null);
        this.addDefault(defaults, Messages.BookTools, "圈地工具为 {0} 和 {1}。", "0: claim modification tool name; 1:claim information tool name");
        this.addDefault(defaults, Messages.BookDisabledChestClaims, "  本服务器不会在放置箱子时自动为你圈地。", null);
        this.addDefault(defaults, Messages.BookUsefulCommands, "常用命令：", null);
        this.addDefault(defaults, Messages.NoProfanity, "请文明发言。", null);
        this.addDefault(defaults, Messages.IsIgnoringYou, "该玩家已屏蔽你。", null);
        this.addDefault(defaults, Messages.ConsoleOnlyCommand, "此命令只能在服务器控制台执行。", null);
        this.addDefault(defaults, Messages.WorldNotFound, "未找到该世界。", null);
        this.addDefault(defaults, Messages.TooMuchIpOverlap, "当前有太多玩家使用与你相同的 IP 地址登录。", null);

        this.addDefault(defaults, Messages.StandInSubclaim, "请站在子领地内以限制其权限。", null);
        this.addDefault(defaults, Messages.SubclaimRestricted, "此子领地将不再继承父领地的权限。", null);
        this.addDefault(defaults, Messages.SubclaimUnrestricted, "此子领地将恢复继承父领地的权限。", null);

        this.addDefault(defaults, Messages.NetherPortalTrapDetectionMessage, "你似乎被困在下界传送门中。如果确实如此，几秒后将尝试救援！", "Sent to player on join, if they left while inside a nether portal.");

        //load the config file
        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(messagesFilePath));

        //for each message ID
        for (Messages messageID : messageIDs)
        {
            //get default for this message
            CustomizableMessage messageData = defaults.get(messageID.name());

            //if default is missing, log an error and use some fake data for now so that the plugin can run
            if (messageData == null)
            {
                GriefPrevention.AddLogEntry("Missing message for " + messageID.name() + ".  Please contact the developer.");
                messageData = new CustomizableMessage(messageID, "Missing message!  ID: " + messageID.name() + ".  Please contact a server admin.", null);
            }

            //read the message from the file, use default if necessary
            this.messages[messageID.ordinal()] = config.getString("Messages." + messageID.name() + ".Text", messageData.text);
            config.set("Messages." + messageID.name() + ".Text", this.messages[messageID.ordinal()]);

            //support color codes
            if (messageID != Messages.HowToClaimRegex)
            {
                this.messages[messageID.ordinal()] = this.messages[messageID.ordinal()].replace('$', (char) 0x00A7);
            }

            if (messageData.notes != null)
            {
                messageData.notes = config.getString("Messages." + messageID.name() + ".Notes", messageData.notes);
                config.set("Messages." + messageID.name() + ".Notes", messageData.notes);
            }
        }

        //save any changes
        try
        {
            config.options().header("Use a YAML editor like NotepadPlusPlus to edit this file.  \nAfter editing, back up your changes before reloading the server in case you made a syntax error.  \nUse dollar signs ($) for formatting codes, which are documented here: http://minecraft.gamepedia.com/Formatting_codes");
            config.save(DataStore.messagesFilePath);
        }
        catch (IOException exception)
        {
            GriefPrevention.AddLogEntry("Unable to write to the configuration file at \"" + DataStore.messagesFilePath + "\"");
        }

        defaults.clear();
        System.gc();
    }

    private void addDefault(HashMap<String, CustomizableMessage> defaults,
                            Messages id, String text, String notes)
    {
        CustomizableMessage message = new CustomizableMessage(id, text, notes);
        defaults.put(id.name(), message);
    }

    synchronized public String getMessage(Messages messageID, String... args)
    {
        String message = messages[messageID.ordinal()];

        for (int i = 0; i < args.length; i++)
        {
            String param = args[i];
            message = message.replace("{" + i + "}", param);
        }

        return message;
    }

    //used in updating the data schema from 0 to 1.
    //converts player names in a list to uuids
    protected List<String> convertNameListToUUIDList(List<String> names)
    {
        //doesn't apply after schema has been updated to version 1
        if (this.getSchemaVersion() >= 1) return names;

        //list to build results
        List<String> resultNames = new ArrayList<>();

        for (String name : names)
        {
            //skip non-player-names (groups and "public"), leave them as-is
            if (name.startsWith("[") || name.equals("public"))
            {
                resultNames.add(name);
                continue;
            }

            //otherwise try to convert to a UUID
            UUID playerID = null;
            try
            {
                playerID = UUIDFetcher.getUUIDOf(name);
            }
            catch (Exception ex) { }

            //if successful, replace player name with corresponding UUID
            if (playerID != null)
            {
                resultNames.add(playerID.toString());
            }
        }

        return resultNames;
    }

    abstract void close();

    private class SavePlayerDataThread extends Thread
    {
        private final UUID playerID;
        private final PlayerData playerData;

        SavePlayerDataThread(UUID playerID, PlayerData playerData)
        {
            this.playerID = playerID;
            this.playerData = playerData;
        }

        public void run()
        {
            //ensure player data is already read from file before trying to save
            playerData.getAccruedClaimBlocks();
            playerData.getClaims();
            asyncSavePlayerData(this.playerID, this.playerData);
        }
    }

    //gets all the claims "near" a location
    Set<Claim> getNearbyClaims(Location location)
    {
        Set<Claim> claims = new HashSet<>();

        Chunk lesserChunk = location.getWorld().getChunkAt(location.subtract(150, 0, 150));
        Chunk greaterChunk = location.getWorld().getChunkAt(location.add(300, 0, 300));

        for (int chunk_x = lesserChunk.getX(); chunk_x <= greaterChunk.getX(); chunk_x++)
        {
            for (int chunk_z = lesserChunk.getZ(); chunk_z <= greaterChunk.getZ(); chunk_z++)
            {
                Chunk chunk = location.getWorld().getChunkAt(chunk_x, chunk_z);
                Long chunkID = getChunkHash(chunk.getBlock(0, 0, 0).getLocation());
                ArrayList<Claim> claimsInChunk = this.chunksToClaimsMap.get(chunkID);
                if (claimsInChunk != null)
                {
                    for (Claim claim : claimsInChunk)
                    {
                        if (claim.inDataStore && claim.getLesserBoundaryCorner().getWorld().equals(location.getWorld()))
                        {
                            claims.add(claim);
                        }
                    }
                }
            }
        }

        return claims;
    }

    //deletes all the land claims in a specified world
    void deleteClaimsInWorld(World world, boolean deleteAdminClaims)
    {
        for (int i = 0; i < claims.size(); i++)
        {
            Claim claim = claims.get(i);
            if (claim.getLesserBoundaryCorner().getWorld().equals(world))
            {
                if (!deleteAdminClaims && claim.isAdminClaim()) continue;
                this.deleteClaim(claim, false, false);
                i--;
            }
        }
    }
}
