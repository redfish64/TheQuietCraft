package com.rareventure.quietcraft;

import com.avaje.ebean.EbeanServer;
import com.rareventure.quietcraft.utils.BlockArea;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Contains standard procedures for handling interaction with the system state and database
 */
public class PlayerManager {

    //TODO 2.1 open bugtracker account or create our own
    //TODO 3 make saying 'hello sailor' cause the person to be immediately transported to the nether
    //with no souls

    //TODO 2.5 investigate resource packs for making a custom item for a soul
    private final QuietCraftPlugin qcp;

    private EbeanServer db;

    private Map<String,QCPlayer> uuidToQCPlayerCache = new HashMap<>();

    public PlayerManager(QuietCraftPlugin qcp)
    {
        this.db = qcp.db;
        this.qcp = qcp;

        db.find(QCPlayer.class).findList().forEach(p -> uuidToQCPlayerCache.put(p.getUuid(),p));
    }

    private void addToPlayerLog(QCPlayer qcPlayer, QCPlayerLog.Action action)
    {
        Bukkit.getLogger().info("Player log "+qcPlayer+" action "+action);
        QCWorld w = qcPlayer.getWorld();
        db.insert(new QCPlayerLog(qcPlayer.getUuid(),new Date(),
                w.getId(), w.getRecycleCounter(),
                action));
    }

    public void onPlayerDeath(Player p, PlayerDeathEvent e) {
        EbeanServer db = QuietCraftPlugin.db;

        int soulCount = getSoulCount(p);

        if(WorldUtil.isNetherWorld(p.getWorld())) {
            //in the nether, souls aren't removed from inventory (are collectable), and the player
            //loses all their souls
            QCPlayer qcPlayer = getQCPlayer(p.getUniqueId().toString());

            if(soulCount > 0) {
                //if they die with at least one soul, they get to go back to their world
                p.sendMessage(Config.NETHER_DEATH_WITH_SOULS_MSG);
                qcPlayer.setSoulsKeptDuringDeath(1);
            }
            else {
                //death with no souls.. well we know what happens here
                qcPlayer.setSoulsKeptDuringDeath(0);

                //we add one soul. This makes it so winning any PVP in the nether
                //gives the victor at least one soul
                addSoulsToInventory(p, 1);
            }

            db.update(qcPlayer);
            return;
        }


        //remove all souls from inventory. We'll place any that shouldn't go with the player as drops later
        for(Iterator<ItemStack> isi = e.getDrops().listIterator(); isi.hasNext();)
        {
            ItemStack is = isi.next();
            if(is.getType().equals(Config.SOUL_MATERIAL_TYPE)
                    && is.getItemMeta().getDisplayName().equals(Config.SOUL_DISPLAY_NAME))
                isi.remove();
        }

        int droppedSouls = 0;

        if(soulCount > Config.MAX_SOULS_HELD_THROUGH_DEATH)
        {
            droppedSouls = soulCount - Config.MAX_SOULS_HELD_THROUGH_DEATH;
            soulCount = Config.MAX_SOULS_HELD_THROUGH_DEATH;

        }

        QCPlayer qcPlayer = getQCPlayer(p.getUniqueId().toString());

        qcPlayer.setSoulsKeptDuringDeath(soulCount);

        db.update(qcPlayer);

        if(droppedSouls > 0)
        {
            Bukkit.getLogger().info("Dropping "+droppedSouls+" soul(s) on death for "+p);
            p.getWorld().dropItemNaturally(p.getLocation(), WorldUtil.createSoulGem(droppedSouls));
        }

        debugPrintPlayerInfo("onPlayerDeath",p);
    }

    private QCPlayer getQCPlayer(String uniqueId) {
        synchronized (uuidToQCPlayerCache)
        {
            return uuidToQCPlayerCache.get(uniqueId);
        }
    }

    /**
     * Returns souls held in inventory
     */
    private int getSoulCount(Player p) {
        int souls = 0;

        for(ItemStack itemStack : p.getInventory())
        {
            if(itemStack == null)
                continue;
            if(itemStack.getType() == Config.SOUL_MATERIAL_TYPE) {
                ItemMeta im = itemStack.getItemMeta();
                if(isSoulMeta(im))
                    souls += itemStack.getAmount();
            }
        }

        return souls;
    }

    private boolean isSoulMeta(ItemMeta im) {
        return im.getDisplayName().equals(Config.SOUL_DISPLAY_NAME);
    }

    public QCPlayer getQCPlayer(Player player) {
        return getQCPlayer(player.getUniqueId().toString());
    }

    public QCPlayer createQCPlayer(Player player, QCWorld w) {
        QCPlayer qcPlayer = new QCPlayer(player.getUniqueId().toString(), w, Config.SOULS_PER_REBIRTH);
        db.insert(qcPlayer);

        synchronized (uuidToQCPlayerCache)
        {
            uuidToQCPlayerCache.put(player.getUniqueId().toString(),qcPlayer);
        }

        return qcPlayer;
    }


    public void onPlayerJoin(Player player) {
        QCPlayer qcPlayer = getQCPlayer(player);

        displayWelcomeMsg(player);

        db.beginTransaction();
        try {
            //new player
            if(qcPlayer == null)
            {
                QCWorld w = qcp.wm.findBestActiveWorldForPlayer(player.getUniqueId().toString());
                qcPlayer = createQCPlayer(player,w);

                player.sendMessage("You have been born in world '"+w.getName()+"'");

                Location spawnLocation = Bukkit.getWorld(w.getName()).getSpawnLocation();
                WorldUtil.makeTeleportLocationSafe(spawnLocation);
                player.teleport(spawnLocation);
                giveInitialPackageToPlayer(player, player.getWorld(), Config.SOULS_PER_REBIRTH, true);

                addToPlayerLog(qcPlayer, QCPlayerLog.Action.JOIN);
            }
            else {
                addToPlayerLog(qcPlayer, QCPlayerLog.Action.JOIN);

                WorldUtil.makeTeleportLocationSafe(player.getLocation());

                player.sendMessage("You are in world '" +
                        player.getWorld().getName() + "'");

                int soulCount = getSoulCount(player);
                if(soulCount > Config.MAX_SOULS_HELD_THROUGH_DEATH)
                {
                    player.sendMessage(ChatColor.RED+"WARNING!!! "+ChatColor.WHITE+"You are carrying over "
                            +"the maximum number of souls. If you die, you'll drop "
                            +(soulCount - Config.MAX_SOULS_HELD_THROUGH_DEATH)+" of your "+soulCount+
                    " souls on the ground.");
                }
            }
            db.commitTransaction();
        }
        finally {
            db.endTransaction();
        }

        debugPrintPlayerInfo("onPlayerJoin",player);
    }

    private void displayWelcomeMsg(Player player) {
        Config.WELCOME_MSG.forEach(m -> player.sendMessage(m));
    }

    private void debugPrintPlayerInfo(String message, Player player) {
        QCPlayer p = getQCPlayer(player);
        Bukkit.getLogger().info(message+", "+p);
    }

    /**
     * Gives the player their initial items when they die or join a visited world
     * @param soulCount souls remaining
     * @param isFirstAppearance true if this is the first time the player has appeared in
     *                          this visited world
     */
    private void giveInitialPackageToPlayer(Player player, World world, int soulCount, boolean isFirstAppearance) {
        Inventory i = player.getInventory();
        i.clear();

        if(isFirstAppearance)
        {
            addPortalKeysToInventory(player, 1);

            //we give a bed to a player so they don't end up spawning at the spawn point and getting killed all the
            //ti1me if some griefer kills them
            i.addItem(new ItemStack(Material.BED));
        }

        addSoulsToInventory(player, soulCount);
    }

    public void addSoulsToInventory(Player player, int soulCount) {
        WorldUtil.addItemsToInventory(player, soulCount, Config.SOUL_MATERIAL_TYPE,
                Config.SOUL_DISPLAY_NAME, Config.SOUL_LORE);
    }

    public void addPortalKeysToInventory(Player player, int count) {
        WorldUtil.addItemsToInventory(player, count, Config.PORTAL_KEY_MATERIAL_TYPE,
                Config.PORTAL_KEY_NAME, Config.PORTAL_KEY_LORE);
    }

    //TODO 3 make sure player can survive with nether care pack

    /**
     * Gives the player their initial items when they die and join the nether
     */
    private void giveNetherCarePack(Player player) {
        Inventory i = player.getInventory();
        i.clear();

        i.addItem(new ItemStack(Material.LOG, 30));
        i.addItem(new ItemStack(Material.COBBLESTONE, 40));
        i.addItem(new ItemStack(Material.SAPLING, 5));
        i.addItem(new ItemStack(Material.RAW_BEEF, 5));
        i.addItem(new ItemStack(Material.SEEDS, 10));
        i.addItem(new ItemStack(Material.DIRT, 64));
        i.addItem(new ItemStack(Material.DIRT, 64));
        i.addItem(new ItemStack(Material.DIRT, 64));
        i.addItem(new ItemStack(Material.STRING, 5));
    }

    public void onPlayerQuit(Player player) {
        addToPlayerLog(getQCPlayer(player), QCPlayerLog.Action.QUIT);
        debugPrintPlayerInfo("onPlayerQuit",player);
    }

    //TODO 2.5 maybe lose half + 1 souls in nether and teleported to bed...
    //problem: what if the user only has one soul in inventory.. do they die,
    //or do we drop their soul and teleport them back. If we do the second,
    //then hell is even safer then that normal world if you have one soul in
    //inventory.

    //TODO 3 eliminate "bed obstructed" message... difficult, may need to filter packets???
    /**
     * This is called after onDeath() when the player has clicked the respawn button
     * and is ready to teleport to his new home.
     *
     * @return location to teleport to, or null if player should respawn as normal
     */
    public Location onRespawn(Player p) {
        QCPlayer player = qcp.pm.getQCPlayer(p);
        QCWorld w;
        int soulCount = player.getSoulsKeptDuringDeath();

        if(soulCount > 0) {
            db.beginTransaction();
            try{
                soulCount--;
                //just incase something funny happens, we subtract a soul from their death count
                player.setSoulsKeptDuringDeath(soulCount);
                db.update(player);

                w = player.getWorld();

                if(soulCount == 0)
                    p.sendMessage("You have no souls left, you are not long for this world.");
                else if(soulCount == 1)
                    p.sendMessage("You feel a lot less than whole. You have one soul left.");
                else if(soulCount < Config.SOULS_PER_REBIRTH)
                    p.sendMessage("You feel a little less than whole. You have "+(soulCount)+" souls left.");
                else if(soulCount == Config.SOULS_PER_REBIRTH)
                    p.sendMessage("You feel spiritually content. You have "+(soulCount)+" souls left.");
                else if(soulCount < Config.SOULS_PER_REBIRTH *2)
                    p.sendMessage("You feel spiritually bloated, as if you are greater than mortal. You have "+(soulCount)+" souls left.");
                else if(soulCount < Config.SOULS_PER_REBIRTH *5)
                    p.sendMessage("You feel like a ethereal king, fear your wrath! You have "+(soulCount)+" souls left.");
                else if(soulCount < Config.SOULS_PER_REBIRTH *7)
                    p.sendMessage("You feel like a soul sucking demon. Who has fallen so you may grow so large? You have "+(soulCount)+" souls left.");
                else if(soulCount < Config.SOULS_PER_REBIRTH *9)
                    p.sendMessage("You've become almost immortal, flee, mortals, flee! You have "+(soulCount)+" souls left.");
                else if(soulCount < Config.SOULS_PER_REBIRTH *11)
                    p.sendMessage("Are you a god? You have "+(soulCount)+" souls left.");
                else if(soulCount < Config.SOULS_PER_REBIRTH *13)
                    p.sendMessage("Yes, you are a god. You have "+(soulCount)+" souls left.");
                else
                    p.sendMessage("You can stop now... You have "+(soulCount)+" souls left.");

                giveInitialPackageToPlayer(p, p.getWorld() , soulCount, false);
                db.commitTransaction();
            } finally {
                db.endTransaction();
            }

            debugPrintPlayerInfo("onRespawn with souls left",p);
            Location bedSpawnLocation = p.getBedSpawnLocation();
            if(bedSpawnLocation != null) {
                debugPrintPlayerInfo("respawning to bed", p);
                return bedSpawnLocation;
            }

            debugPrintPlayerInfo("respawning to spawn point", p);
            return Bukkit.getWorld(w.getName()).getSpawnLocation();
        }

        p.setBedSpawnLocation(null);

        db.beginTransaction();
        try{
            addToPlayerLog(player, QCPlayerLog.Action.PERMA_DEATH);

            w = qcp.wm.findOrCreateBestWorldForDeadPlayer(p);

            player.setWorld(w);
            db.update(player);
            Bukkit.getLogger().info("onRespawn set world "+w+","+player);

            addToPlayerLog(player, QCPlayerLog.Action.MOVED_TO_WORLD);

           db.commitTransaction();
        } finally {
           db.endTransaction();
        }

        debugPrintPlayerInfo("onRespawn without souls left",p);

        Location spawnLocation;

        if(WorldUtil.isNetherWorld(w.getName())) {
            //we only want this message to appear the first time the player entered the nether
            if(!WorldUtil.isNetherWorld(p.getWorld().getName()))
                p.sendMessage("The world slowly comes into place. You blink twice... wait a minute, where *ARE* you?");
            //TODO 3 maybe put up a message indicating how much time before an abandoned world can be
            //revisited


            //in the nether the spawn location is always random, to prevent someone from creating a booby
            //trap at a spawn location
            spawnLocation = WorldUtil.getRandomSpawnLocation(Bukkit.getWorld(WorldUtil.NETHER_WORLD_NAME),
                    Config.NETHER_SPAWN_RNP);

            giveNetherCarePack(p);
        }
        else
        {
            World spawnedWorld = Bukkit.getWorld(w.getName());

            giveInitialPackageToPlayer(p,spawnedWorld, Config.SOULS_PER_REBIRTH, true);
            p.sendMessage("You have been reborn into " + w.getName());

            spawnLocation = w.getSpawnLocation(spawnedWorld);
        }


        return spawnLocation;

    }

    /**
     * Returns players nearby a location
     * @param l location
     * @param maxDistance max distance from location
     * @return players within max distance of the location
     */
    public static List<Player> getNearbyPlayers(Location l, int maxDistance) {
        List<Player> players = new ArrayList<>();
        for(Player p : Bukkit.getOnlinePlayers())
        {
            if(p.getLocation().distance(l) < maxDistance)
                players.add(p);
        }

        return players;
    }

    /**
     * Searches a players inventory for a portal key
     *
     * @param p player
     * @return portal key in players inventory, or null if it doesn't exist
     */
    public ItemStack getPortalKey(Player p) {
        for(ItemStack i : p.getInventory())
        {
            if(i != null && i.getType() == Config.PORTAL_KEY_MATERIAL_TYPE && i.getItemMeta() != null
                    && i.getItemMeta().getDisplayName() != null
                    && i.getItemMeta().getDisplayName().equals(Config.PORTAL_KEY_NAME))
                return i;
        }

        return null;
    }

    /**
     * Called when a player is about to be teleported by a portal
     */
    public void onPlayerPortalEvent(PlayerPortalEvent event) {
        Player player = event.getPlayer();

        BlockArea b = WorldUtil.findActivePortal(event.getFrom());

        if (b == null) {
            return;
        }

        Location l = WorldUtil.getRepresentativePortalLocation(b);
        Bukkit.getLogger().info("Player portal location"+l);

        QCPortalLink pl = qcp.portalManager.getPortalLinkForLocation(l);
        Bukkit.getLogger().info("Portal link for location " +pl);

        if(pl == null) {
            Bukkit.getLogger().info("Could not find link for " +l);
            WorldUtil.destroyPortal(l);
            return;
        }

        QCWorld w1 = qcp.wm.getQCWorld(pl.getWorldId1());
        QCWorld w2 = qcp.wm.getQCWorld(pl.getWorldId2());

        QCWorld fw, tw;
        Location otherLocation;

        if(pl.isLocationAtP1(qcp.wm,l))
        {
            fw = w1;
            tw = w2;
            otherLocation = pl.getLoc2();
        }
        else
        {
            fw = w2;
            tw = w1;
            otherLocation = pl.getLoc1();
        }

        //we add one for the soul of the player himself. In the nether, he will drop one soul for it
        int souls = getSoulCount(player) + 1;

        //check if the outflow would be too much
        float outflow = fw.calcSoulOutflowHours(souls);
        if(outflow > Config.MAX_ALLOWED_SOUL_OUTFLOW_PER_HOUR)
        {
            float waitTime = fw.calcSoulOutflowWaitHours(souls, Config.MAX_ALLOWED_SOUL_OUTFLOW_PER_HOUR);
            int soulsAllowedForTeleport = fw.calcSoulsAllowedForTeleport(Config.MAX_ALLOWED_SOUL_OUTFLOW_PER_HOUR);

            if(soulsAllowedForTeleport >= 1 ) {
                if(souls > 1)
                    player.sendMessage(String.format("You are trying to leave with too many souls. Wait "
                            + "%s or remove %d souls from inventory",
                            calcTimeFromHours(waitTime),
                            souls - soulsAllowedForTeleport));
                else
                    player.sendMessage("You are trying to leave too soon. Wait "+calcTimeFromHours(waitTime));
            }
            else
            {
                float oneSoulWaitTime = fw.calcSoulOutflowWaitHours(1, Config.MAX_ALLOWED_SOUL_OUTFLOW_PER_HOUR);
                player.sendMessage(String.format("You are trying to leave with too many souls. Wait %s" +
                    " or remove all souls from inventory and wait %s",
                        calcTimeFromHours(waitTime), calcTimeFromHours(oneSoulWaitTime)));
            }

            //teleport the user a little outside the portal
            Location teleportLocation = WorldUtil.findPortalTeleportPlaceForUser(l);
            WorldUtil.makeTeleportLocationSafe(teleportLocation);
            player.teleport(teleportLocation);
            event.setCancelled(true);
            return;
        }


        qcp.db.beginTransaction();
        try {
            fw.addSouls(-souls);
            tw.addSouls(souls);
            qcp.db.update(fw);
            qcp.db.update(tw);

            qcp.db.commitTransaction();
        }
        finally {
            qcp.db.endTransaction();
        }

        //TODO 2.1 fix user getting pushed into a wall a little after teleporting (I know this, because one time
        // I teleported with 1/2 heart, and died, and the reason was suffication in dirt wall)
        if(WorldUtil.isNetherWorld(tw.getName()))
        {
            if(Config.ENTER_NETHER_WARNING.length() > 0)
                player.sendMessage(Config.ENTER_NETHER_WARNING);
        }

        Location teleportLocation = WorldUtil.findPortalTeleportPlaceForUser(otherLocation);
        WorldUtil.makeTeleportLocationSafe(teleportLocation);
        player.teleport(teleportLocation);
        event.setCancelled(true);
    }

    private String calcTimeFromHours(float waitTime) {
        if(waitTime > 48)
        {
            return String.format("%8.3f days",waitTime/24);
        }
        if(waitTime > 2)
        {
            return String.format("%8.3f hours",waitTime);
        }
        if(waitTime > 2f/60)
        {
            return String.format("%8.3f minutes",waitTime*60);
        }
        return String.format("%8.3f seconds",waitTime*3600);
    }

    public void onPlayerBedEnterEvent(PlayerBedEnterEvent event) {
        QCWorld bedWorld = qcp.wm.getQCWorld(event.getBed().getWorld().getName());
        QCPlayer qcPlayer = getQCPlayer(event.getPlayer());
        if(qcPlayer.getWorldId() != bedWorld.getId()) {
            Bukkit.getLogger().info("Moving player "+event.getPlayer().getName()+" to world "
                    +bedWorld.getName()
            +" because they slept in a bed");

            qcp.db.beginTransaction();
            try {
                qcPlayer.setWorld(bedWorld);
                qcp.db.update(qcPlayer);
                addToPlayerLog(qcPlayer, QCPlayerLog.Action.MOVED_TO_WORLD);
                qcp.db.commitTransaction();
            }
            finally
            {
                qcp.db.endTransaction();
            }
        }
    }

}
