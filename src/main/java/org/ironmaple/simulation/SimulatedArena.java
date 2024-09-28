package org.ironmaple.simulation;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.wpilibj.TimedRobot;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import org.dyn4j.dynamics.Body;
import org.dyn4j.dynamics.BodyFixture;
import org.dyn4j.geometry.Convex;
import org.dyn4j.geometry.Geometry;
import org.dyn4j.geometry.MassType;
import org.dyn4j.world.PhysicsWorld;
import org.dyn4j.world.World;
import org.ironmaple.simulation.drivesims.AbstractDriveTrainSimulation;
import org.ironmaple.simulation.gamepieces.GamePieceOnFieldSimulation;
import org.ironmaple.simulation.gamepieces.GamePieceProjectile;
import org.ironmaple.utils.mathutils.GeometryConvertor;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

public abstract class SimulatedArena {
    public static final int SIMULATION_SUB_TICKS_IN_1_PERIOD = 5;
    public static final double SIMULATION_DT = TimedRobot.kDefaultPeriod / SIMULATION_SUB_TICKS_IN_1_PERIOD;

    protected final World<Body> physicsWorld;
    protected final AbstractDriveTrainSimulation mainRobot;
    protected final Set<AbstractDriveTrainSimulation> driveTrainSimulations;
    protected final Set<GamePieceOnFieldSimulation> gamePieces;
    protected final Set<GamePieceProjectile> gamePieceProjectile;
    protected final List<Runnable> simulationSubTickActions;
    private final List<IntakeSimulation> intakeSimulations;

    public SimulatedArena(AbstractDriveTrainSimulation mainRobot, FieldObstaclesMap obstaclesMap) {
        this.physicsWorld = new World<>();
        this.physicsWorld.setGravity(PhysicsWorld.ZERO_GRAVITY);
        for (Body obstacle: obstaclesMap.obstacles)
            this.physicsWorld.addBody(obstacle);
        this.mainRobot = mainRobot;
        this.driveTrainSimulations = new HashSet<>();
        addDriveTrainSimulation(mainRobot);
        simulationSubTickActions = new ArrayList<>();
        this.gamePieces = new HashSet<>();
        this.gamePieceProjectile = new HashSet<>();
        this.intakeSimulations = new ArrayList<>();
    }

    /**
     * <p>register a runnable action that is called in every simulation sub-tick</p>
     * <p>FOR TESTING ONLY</p>
     * */
    @Deprecated
    public void addSimulationSubTickAction(Runnable action) {
        this.simulationSubTickActions.add(action);
    }

    protected void addIntakeSimulation(IntakeSimulation intakeSimulation) {
        this.intakeSimulations.add(intakeSimulation);
        this.physicsWorld.addContactListener(intakeSimulation.getGamePieceContactListener());
    }


    public void simulationPeriodic() {
        final long t0 = System.nanoTime();
        competitionPeriodic();
        // move through a few sub-periods in each update
        for (int i = 0; i < SIMULATION_SUB_TICKS_IN_1_PERIOD; i++)
            simulationSubTick();

        SmartDashboard.putNumber(
                "MapleArenaSimulation/Dyn4j Simulator CPU Time MS",
                (System.nanoTime() - t0) / 1000000.0
        );
    }

    private void simulationSubTick() {
        for (AbstractDriveTrainSimulation driveTrainSimulation:driveTrainSimulations)
            driveTrainSimulation.simulationSubTick();

        GamePieceProjectile.updateGamePieceProjectiles(this, this.gamePieceProjectile);

        this.physicsWorld.step(1, SIMULATION_DT);

        for (IntakeSimulation intakeSimulation:intakeSimulations)
            while (!intakeSimulation.getGamePiecesToRemove().isEmpty())
                removeGamePiece(intakeSimulation.getGamePiecesToRemove().poll());

        for (Runnable runnable:simulationSubTickActions)
            runnable.run();
    }

    public void addDriveTrainSimulation(AbstractDriveTrainSimulation driveTrainSimulation) {
        this.physicsWorld.addBody(driveTrainSimulation);
        this.driveTrainSimulations.add(driveTrainSimulation);
    }

    public void addGamePiece(GamePieceOnFieldSimulation gamePiece) {
        this.physicsWorld.addBody(gamePiece);
        this.gamePieces.add(gamePiece);
    }

    public void addGamePieceProjectile(GamePieceProjectile gamePieceProjectile) {
        this.gamePieceProjectile.add(gamePieceProjectile);
        gamePieceProjectile.launch();
    }

    public void removeGamePiece(GamePieceOnFieldSimulation gamePiece) {
        this.physicsWorld.removeBody(gamePiece);
        this.gamePieces.remove(gamePiece);
    }

    public void clearGamePieces() {
        for (GamePieceOnFieldSimulation gamePiece: this.gamePieces)
            this.physicsWorld.removeBody(gamePiece);
        this.gamePieces.clear();
    }

    public List<Pose3d> getGamePiecesByType(String type) {
        final List<Pose3d> gamePiecesPoses = new ArrayList<>();
        for (GamePieceOnFieldSimulation gamePiece:gamePieces)
            if (Objects.equals(gamePiece.type, type))
                gamePiecesPoses.add(gamePiece.getPose3d());

        for (GamePieceProjectile gamePiece:gamePieceProjectile)
            if (Objects.equals(gamePiece.gamePieceType, type))
                gamePiecesPoses.add(gamePiece.getPose3d());

        return gamePiecesPoses;
    }

    public void resetFieldForAuto() {
        clearGamePieces();
        placeGamePiecesOnField();
    }

    /**
     * do field reset by placing all the game-pieces on field(for autonomous)
     * */
    public abstract void placeGamePiecesOnField();

    /**
     * update the score counts & human players periodically
     * implement this method in current year's simulation
     * */
    public abstract void competitionPeriodic();

    /**
     * stores the obstacles on a competition field, which includes the border and the game pieces
     * */
    public static abstract class FieldObstaclesMap {
        private final List<Body> obstacles = new ArrayList<>();

        protected void addBorderLine(Translation2d startingPoint, Translation2d endingPoint) {
            final Body obstacle = getObstacle(Geometry.createSegment(
                    GeometryConvertor.toDyn4jVector2(startingPoint),
                    GeometryConvertor.toDyn4jVector2(endingPoint)
            ));
            obstacles.add(obstacle);
        }

        protected void addRectangularObstacle(double width, double height, Pose2d pose) {
            final Body obstacle = getObstacle(Geometry.createRectangle(
                    width, height
            ));

            obstacle.getTransform().set(GeometryConvertor.toDyn4jTransform(pose));
            obstacles.add(obstacle);
        }

        private static Body getObstacle(Convex shape) {
            final Body obstacle = new Body();
            obstacle.setMass(MassType.INFINITE);
            final BodyFixture fixture = obstacle.addFixture(shape);
            fixture.setFriction(0.6);
            fixture.setRestitution(0.3);
            return obstacle;
        }
    }
}
