
package com.rareventure.quietcraft;

import com.avaje.ebean.validation.NotNull;

import javax.persistence.*;

@Entity()
@Table(name="qc_world")
public class QCWorld {
    /**
     * Current number of players in the world
     */
    //TODO 2 populate
    @Transient
    public int currentPlayerCount;

    /**
     * Cached value of how many times the world was visited
     */
    @Transient
    private int timesVisited;

    @Id
    private long id;

    /**
     * A visit id is just like an id, but changes when the world is abandoned for too long.
     * This allows worlds to be revisited after awhile, and keeps people from having to be
     * spawned in the nether without ever getting back to a real world.
     */
    @NotNull
    private long visitId;

    @NotNull
    private String name;

    public QCWorld()
    {}

    public QCWorld(long id, long visitId, String name) {
        this.id = id;
        this.visitId = visitId;
        this.name = name;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getVisitId() {
        return visitId;
    }

    public void setVisitId(long visitId) {
        this.visitId = visitId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getWorldVisitedCount(QuietCraftPlugin qcp) {
        //TODO 2 FIXME
        return  timesVisited = 0;
    }
}

