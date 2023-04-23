package net.oneandone.kafka.clusteredjobs;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author aschoerk
 */
abstract class TaskBase implements Task {
    Logger logger = LoggerFactory.getLogger(TaskBase.class);

    private TaskStateEnum currentState;
    private Instant lastClaimedInfo;
    private Instant stateStarted;
    private Instant lastStartup;
    private Instant claimingTimestamp;

    @Override
    public TaskStateEnum getLocalState() {
        return currentState;
    }

    @Override
    public void setLocalState(final TaskStateEnum stateToSet) {
        logger.info("Setting state: {}", stateToSet);
        switch (stateToSet) {
            case CLAIMING:
                if(!TaskStateEnum.CLAIMING.equals(currentState)) {
                    // claiming initiated, now start waiting
                    stateStarted = Instant.now();
                }
                currentState = stateToSet;
                break;
            case HANDLING_BY_OTHER:
            case CLAIMED_BY_OTHER:
                sawClaimedInfo();
                setClaimingTimestamp(null);
                currentState = stateToSet;
                break;
            case HANDLING_BY_NODE:
                if(currentState != TaskStateEnum.HANDLING_BY_OTHER) {
                    stateStarted = Instant.now();
                    lastStartup = Instant.now();
                }
                currentState = stateToSet;
                break;
            case UNCLAIM:
                if(currentState == TaskStateEnum.CLAIMED_BY_NODE || currentState == TaskStateEnum.HANDLING_BY_NODE) {
                    currentState = TaskStateEnum.UNCLAIM;
                }
                break;
        }
        logger.info("Result  state: {}", stateToSet);
    }

    @Override
    public Instant getLastClaimedInfo() {
        return lastClaimedInfo;
    }

    @Override
    public void sawClaimedInfo() {
        this.lastClaimedInfo = Instant.now();
    }

    @Override
    public Instant getClaimingSet() {
        if(currentState.equals(TaskStateEnum.CLAIMING)) {
            return stateStarted;
        }
        else {
            throw new RuntimeException("Should not ask for claimingStarted if not CLAIMING");
        }
    }

    @Override
    public Instant getClaimingTimestamp() {
        return claimingTimestamp;
    }

    @Override
    public void setClaimingTimestamp(final Instant claimingTimestampP) {
        this.claimingTimestamp = claimingTimestampP;
    }

    @Override
    public Instant getHandlingStarted() {
        if(currentState.equals(TaskStateEnum.HANDLING_BY_NODE)) {
            return stateStarted;
        }
        else {
            throw new RuntimeException("Should not ask for handlingStarted if not HANDLING");
        }
    }

    @Override
    public void unclaim() {
        if(getLocalState() == TaskStateEnum.CLAIMED_BY_NODE || getLocalState() == TaskStateEnum.HANDLING_BY_NODE) {
            setLocalState(TaskStateEnum.UNCLAIM);
        }
    }

    @Override
    public Instant getLastStartup() {
        return lastStartup;
    }

}
