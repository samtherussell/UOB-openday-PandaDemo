package client.application;

import scotlandyard.*;
import client.model.*;
import client.algorithms.*;

import java.util.*;
import java.util.concurrent.*;
import javax.swing.JLabel;

/**
 * A class that runs on a new Thread and starts a new instance of the game.
 */

public class ScotlandYardGame implements Player, Runnable {
    
    private ScotlandYardModel model;
    private SaveGame saveGame = null;
    private int numPlayers;
    private List<GamePlayer> players;
    private String gameName;
    private String graphName;
    private InputPDA pda;
    private ThreadCommunicator threadCom;
    private FileAccess fileAccess;
    private boolean outOfTime = false;
    private Dijkstra routeFinder;
    private boolean waitForHint = false;
    private boolean firstRound = true;
    private boolean replaying = false;
    
    private final int kDetectiveWait = 3000;
    private final int kMoveWait = 2000;
    
    private int[] detectiveLocations = {26, 29, 50, 53, 91, 94, 103, 112, 117, 123, 138, 141, 155, 174};
    private int[] mrXLocations = {35, 45, 51, 71, 78, 104, 106, 127, 132, 166, 170, 172};
    
    /**
     * Constructs a new ScotlandYardGame object.
     *
     * @param numPlayers the number of players in the game.
     * @param gameName the name of the game - for the game save.
     * @param graphName the name of the graph file for the game.
     * @param threadCom the TreadCommunicator object to 
     * communicate between Threads.
     */
    public ScotlandYardGame(int numPlayers, String gameName, String graphName, ThreadCommunicator threadCom) {
        try {
            this.threadCom = threadCom;
            this.numPlayers = numPlayers;
            this.gameName = gameName;
            this.graphName = graphName;
            routeFinder = new Dijkstra(graphName);
            List<Boolean> rounds = getRounds();
            model = new ScotlandYardModel(numPlayers - 1, rounds, graphName);
            int randMrXLocation = randomMrXLocation();
            int[] randDetectiveLocations = randomDetectiveLocations(numPlayers - 1);
            this.players = initialiseGame(randMrXLocation, randDetectiveLocations);
            saveGame = new SaveGame(numPlayers, graphName, gameName);
            saveGame.setMrXLocation(randMrXLocation);
            saveGame.setDetectiveLocations(randDetectiveLocations);
            fileAccess = new FileAccess();
            
            long startTime = System.nanoTime();
            routeFinder.getRoute(1, 171, getPlayerTicketsRoute(Colour.Black));
            long endTime = System.nanoTime();
            System.err.println("TIME:" + (endTime - startTime));
        } catch (Exception e) {
            System.err.println("Error setting up new game :" + e);
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Constructs a ScotlandYardGame from a game save.
     *
     * @param gameName the name of the game to load.
     * @param threadCom the TreadCommunicator object to
     * communicate between Threads.
     */
    public ScotlandYardGame(String gameName, ThreadCommunicator threadCom) {
        try {
            this.threadCom = threadCom;
            fileAccess = new FileAccess();
            saveGame = fileAccess.loadGame(gameName);
            this.numPlayers = saveGame.getNumberOfPlayers();
            this.gameName = saveGame.getGameName();
            this.graphName = saveGame.getGraphFileName();
            routeFinder = new Dijkstra(graphName);
            List<Boolean> rounds = getRounds();
            model = new ScotlandYardModel(numPlayers - 1, rounds, graphName);
            players = initialiseGame(saveGame.getMrXLocation(), saveGame.getDetectiveLocations());
            replaying = true;
        } catch (Exception e) {
            System.err.println("Error loading game :" + e.getStackTrace());
            System.exit(1);
        }
    }
    
    /**
     * The run method called when the Thread is started.
     * Starts the game.
     */
    public void run() {
        try {
            List<Boolean> rounds = getRounds();
            pda = new InputPDA();
            initialiseViews(players);
            fileAccess.saveGame(saveGame);
            threadCom.putUpdate("update_moves", MoveTicket.instance(Colour.Black, null, model.getTruePlayerLocation(Colour.Black)));
            model.start();
            fileAccess.saveGame(saveGame);
            threadCom.putUpdate("stop_timer", true);
            Set<Colour> winningPlayers = model.getWinningPlayers();
            sendNotification(getWinningMessage(winningPlayers));
            wait(5000);
            threadCom.putUpdate("end_game", true);
        } catch (Exception e) {
            System.err.println("Error playing game :" + e);
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    // Returns the List of Booleans determining when Mr X is visible.
    // Only the advanced version of the game is supported at this time.
    // @return the List of Booleans determining when Mr X is visible.
    private List<Boolean> getRounds() {
        return Arrays.asList(false, false, false, true, false,
                             false, false, false, true, false,
                             false, false, false, true, false,
                             false, false, false, true, false,
                             false, false, false, true);
    }
    
    // Returns the List of players in the game.
    // @param mrXLocation the initial location of Mr X.
    // @param detectiveLocations the initial locations of the Detectives.
    // @return the List of players in the game.
    private List<GamePlayer> initialiseGame(int mrXLocation, int[] detectiveLocations) {
        List<GamePlayer> players = new ArrayList<GamePlayer>();
        Colour[] colours = Colour.values();
        
        Map<Ticket, Integer> mrXTickets = new HashMap<Ticket, Integer>();
        mrXTickets.put(Ticket.Taxi, 10);
        mrXTickets.put(Ticket.Bus, 10);
        mrXTickets.put(Ticket.Underground, 10);
        mrXTickets.put(Ticket.Double, 2);
        mrXTickets.put(Ticket.Secret, 5);
        model.join(this, colours[0], mrXLocation, mrXTickets);
        players.add(new GamePlayer(this, colours[0], 0, mrXTickets));
        
        for (int i = 1; i < numPlayers; i++) {
            Map<Ticket, Integer> detectiveTickets = new HashMap<Ticket, Integer>();
            detectiveTickets.put(Ticket.Taxi, 11);
            detectiveTickets.put(Ticket.Bus, 8);
            detectiveTickets.put(Ticket.Underground, 4);
            detectiveTickets.put(Ticket.Double, 0);
            detectiveTickets.put(Ticket.Secret, 0);
            model.join(this, colours[i], detectiveLocations[i - 1], detectiveTickets);
            players.add(new GamePlayer(this, colours[i], detectiveLocations[i - 1], detectiveTickets));
        }
        return players;
    }
    
    // Returns an array of random locations for the detectives.
    // @param noOfDetectives the number of detectives to generate random locations for.
    // @return an array of random locations for the detectives.
    private int[] randomDetectiveLocations(int noOfDetectives) {
        int[] locations = new int[noOfDetectives];
        List<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < 14; i++) {
            list.add(i);
        }
        Collections.shuffle(list);
        for (int i = 0; i < noOfDetectives; i++) {
            locations[i] = detectiveLocations[list.get(i)];
        }
        return locations;
    }
    
    // Returns a random location for Mr X.
    // @return a random location for Mr X.
    private int randomMrXLocation() {
        Random random = new Random();
        return mrXLocations[random.nextInt(12)];
    }
    
    /**
     * Returns the Move chosen by the player.
     * Also updates the views.
     * 
     * @param location the actual location of the player.
     * @param moves the List of valid Moves the player can take.
     * @return the Move chosen by the player.
     */
    public Move notify(int location, Set<Move> moves) {
        updateUI(location, model.getCurrentPlayer(), moves);
        Move move = null;
        while (true) {
            if (saveGame.hasSavedMove()) {
                move = saveGame.getSavedMove();
                wait(kMoveWait);
                break;
            } else {
                if (replaying) {
                    threadCom.putUpdate("send_notification", getMessage(model.getCurrentPlayer()));
                    threadCom.putUpdate("update_board", MoveTicket.instance(Colour.Black, null, model.getPlayerLocation(Colour.Black)));
                }
                replaying = false;
            }
            String eventId = (String) threadCom.takeEvent();
            Object eventObject = threadCom.takeEvent();
            decodeEvents(eventId, eventObject);
            
            if (pda.isAccepted()) {
                move = pda.createMove(model.getCurrentPlayer());
                if (moves.contains(move)) {
                    threadCom.putUpdate("highlight_node", 0);
                    break;
                } else {
                    pda.reset();
                    sendNotification("Invalid move, please try again.");
                }
            } else if (outOfTime) {
                move = moves.iterator().next();
                sendNotification("Out of time, a move has been chosen for you.");
                break;
            } else if (moves.iterator().next() instanceof MovePass) {
                move = moves.iterator().next();
                sendNotification("You don't have any valid moves.");
                break;
            }
        }
        updateUI(move);
        outOfTime = false;
        pda.reset();
        saveGame.addMove(move);
        return move;
    }
    
    // Decodes the id from the queue and performs the appropriate action.
    // @param id the id from the queue.
    // @param object the object from the queue.
    private void decodeEvents(String id, Object object) {
        if (id.equals("node_clicked")) {
            Integer location = (Integer) object;
            if (waitForHint) {
                Colour player = model.getCurrentPlayer();
                Integer playerLoc = model.getTruePlayerLocation(player);
                threadCom.putUpdate("show_route", routeFinder.getRoute(playerLoc, location, getPlayerTicketsRoute(player)));
                
                waitForHint = false;
            } else {
                threadCom.putUpdate("highlight_node", location);
                pda.transition(location);
            }
        } else if (id.equals("timer_fired")) {
            outOfTime = true;
        } else if (id.equals("ticket_clicked")) {
            Ticket ticket = (Ticket) object;
            threadCom.putUpdate("highlight_node", 0);
            pda.transition(ticket);
        } else if (id.equals("timer_warning")) {
            sendNotification("Hurry up, 30 seconds left!");
        } else if (id.equals("ticket_clicked")) {
            Ticket ticket = (Ticket) object;
            pda.transition(ticket);
        } else if (id.equals("hint_clicked")) {
            waitForHint = true;
        } else if (id.equals("move_clicked")) {
            JLabel label = (JLabel) object;
            if (model.getCurrentPlayer().equals(Colour.Black)) {
                threadCom.putUpdate("show_location", label);
            }
        } else if (id.equals("save_game")) {
            fileAccess.saveGame(saveGame);
        }
    }
    
    // Initialises the views at the start of a game.
    // @param players the List of players in the game.
    private void initialiseViews(List<GamePlayer> players) {
        threadCom.putUpdate("init_views", players);
    }
    
    // Shows a message to the users.
    // @param message the message to be shown to the users.
    private void sendNotification(String message) {
        threadCom.putUpdate("send_notification", message);
    }
    
    // Updates the UI at the start of a turn.
    // @param location the actual locaton of the player.
    // @param player the colour of the player whose turn it is.
    private void updateUI(Integer location, Colour player, Set<Move> moves) {
        if (replaying) threadCom.putUpdate("update_board", MoveTicket.instance(Colour.Black, null, model.getTruePlayerLocation(Colour.Black)));
        else threadCom.putUpdate("update_board", MoveTicket.instance(Colour.Black, null, model.getPlayerLocation(Colour.Black)));
        updateTickets(player, null);
        if (player.equals(Colour.Black) && !replaying) {
            threadCom.putUpdate("send_notification", "Detectives, Please look away.");
            wait(kDetectiveWait);
        }
        threadCom.putUpdate("valid_moves", moves);
        threadCom.putUpdate("zoom_in", location);
        threadCom.putUpdate("reset_timer", true);
        if (!replaying) threadCom.putUpdate("send_notification", getMessage(player));
    }
    
    // Updates the UI at the end of a turn.
    // @param move the Move that has been chosen by the player.
    private void updateUI(Move move) {
        threadCom.putUpdate("update_board", move);
        updateTickets(move.colour, move);
        wait(500);
        Integer target = getTarget(move);
        if (target != null) threadCom.putUpdate("zoom_in", target);
        wait(kMoveWait);
        threadCom.putUpdate("zoom_out", true);
        wait(kMoveWait);
    }
    
    private Integer getTarget(Move move) {
        if (move instanceof MoveTicket) return ((MoveTicket) move).target;
        else if (move instanceof MoveDouble) return ((MoveTicket) ((MoveDouble) move).move2).target;
        else return null;
    }
    
    private void updateTickets(Colour player, Move move) {
        List<Object> newTickets = new ArrayList<Object>();
        newTickets.add(player);
        Map<Ticket, Integer> tickets = getPlayerTickets(player);
        if (move != null) tickets = adjustTickets(tickets, move);
        newTickets.add(tickets);
        threadCom.putUpdate("update_tickets", newTickets);
    }
    
    private Map<Ticket, Integer> adjustTickets(Map<Ticket, Integer> tickets, Move move) {
        if (move instanceof MoveTicket) {
            MoveTicket moveTicket = (MoveTicket) move;
            Integer ticketNo = tickets.get(moveTicket.ticket);
            tickets.remove(moveTicket.ticket);
            tickets.put(moveTicket.ticket, --ticketNo);
        } else if (move instanceof MoveDouble) {
            MoveDouble moveDouble = (MoveDouble) move;
            MoveTicket move1 = moveDouble.move1;
            MoveTicket move2 = moveDouble.move2;
            Integer doubleNo = tickets.get(Ticket.Double);
            tickets.remove(Ticket.Double);
            tickets.put(Ticket.Double, --doubleNo);
            Integer move1No = tickets.get(move1.ticket);
            tickets.remove(move1.ticket);
            tickets.put(move1.ticket, --move1No);
            Integer move2No = tickets.get(move2.ticket);
            tickets.remove(move2.ticket);
            tickets.put(move2.ticket, --move2No);
        }
        return tickets;
    }
    
    private Map<Ticket, Integer> getPlayerTickets(Colour player) {
        Map<Ticket, Integer> tickets = new HashMap<Ticket, Integer>();
        tickets.put(Ticket.Taxi, model.getPlayerTickets(player, Ticket.Taxi));
        tickets.put(Ticket.Bus, model.getPlayerTickets(player, Ticket.Bus));
        tickets.put(Ticket.Underground, model.getPlayerTickets(player, Ticket.Underground));
        tickets.put(Ticket.Secret, model.getPlayerTickets(player, Ticket.Secret));
        tickets.put(Ticket.Double, model.getPlayerTickets(player, Ticket.Double));
        return tickets;
    }
    
    // Pauses the game thread for the specified time in miliseconds.
    // @param miliseconds the time to pause the game thread for.
    private void wait(int miliseconds) {
        try {
            Thread.sleep(miliseconds);
        } catch (InterruptedException e) {
            System.err.println(e.getStackTrace());
        }
    }
    
    // Returns the message at the start of a turn.
    // @param player the player whose turn it is.
    // @return the message at the start of a turn.
    private String getMessage(Colour player) {
        if (player.equals(Colour.Black)) {
            return "Mr X's turn.";
        } else {
            return player.toString() + " Detective's turn.";
        }
    }
    
    // Returns the message at the end of a game.
    // @param players the Set of winning players.
    // @return the message at the end of a game.
    private String getWinningMessage(Set<Colour> players) {
        if (players.contains(Colour.Black)) return "Mr X wins!";
        return "Detectives win!";
    }
    
    // Returns the tickets a player has.
    // @param colour the color of the player.
    // @return the tickets a player has.
    private Map<Route, Integer> getPlayerTicketsRoute(Colour colour) {
        Map<Route, Integer> tickets = new HashMap<Route, Integer>();
        tickets.put(Route.Taxi, model.getPlayerTickets(colour, Ticket.Taxi));
        tickets.put(Route.Bus, model.getPlayerTickets(colour, Ticket.Bus));
        tickets.put(Route.Underground, model.getPlayerTickets(colour, Ticket.Underground));
        tickets.put(Route.Boat, model.getPlayerTickets(colour, Ticket.Secret));
        return tickets;
    }
    
    /**
     * Saves the game.
     */
    public void saveGame() {
        if (!replaying) fileAccess.saveGame(saveGame);
    }
    
}