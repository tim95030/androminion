package com.vdom.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import java.util.List;
import java.util.Map;

import com.vdom.api.ActionCard;
import com.vdom.api.Card;
import com.vdom.api.CurseCard;
import com.vdom.api.DurationCard;
import com.vdom.api.GameEvent;
import com.vdom.api.GameEvent.Type;
import com.vdom.api.GameEventListener;
import com.vdom.api.TreasureCard;
import com.vdom.api.VictoryCard;
import com.vdom.comms.Comms;
import com.vdom.comms.Event;
import com.vdom.comms.Event.EType;
import com.vdom.comms.Event.EventObject;
import com.vdom.comms.EventHandler;
import com.vdom.comms.GameStatus;
import com.vdom.comms.MyCard;
import com.vdom.comms.NewGame;
import com.vdom.comms.SelectCardOptions;
import com.vdom.core.CardList;
import com.vdom.core.Cards;
import com.vdom.core.ExitException;
import com.vdom.core.Game;
import com.vdom.core.IndirectPlayer;
import com.vdom.core.MoveContext;
import com.vdom.core.Player;
import com.vdom.core.Util;

/**
 * Class that you can use to play remotely.
 * This seems to be the human player
 */
public class RemotePlayer extends IndirectPlayer implements GameEventListener, EventHandler {
    @SuppressWarnings("unused")
    private static final String TAG = "RemotePlayer";

    static int nextPort = 2255;
    static final int NUM_RETRIES = 3; // times to try anything before giving up.
    static int maxPause = 300000; // Maximum time to wait for new player to connect = 5 minutes in ms;
    private static VDomServer vdomServer = null; // points to the VDomServer object

    // private static final String DISTINCT_CARDS = "Distinct Cards";

    Comms comm = null;
    // communication thread handled internally now
    // Thread commThread;
    private int myPort = 0;

    protected String name;
    private HashMap<String, Integer> cardNamesInPlay = new HashMap<String, Integer>();
    private ArrayList<Card> cardsInPlay = new ArrayList<Card>();
    private ArrayList<Player> allPlayers = new ArrayList<Player>();
    private MyCard[] myCardsInPlay;

    private ArrayList<Card> playedCards = new ArrayList<Card>();
    private ArrayList<Boolean> playedCardsNew = new ArrayList<Boolean>();

    private boolean hasJoined = false;
    private Object hasJoinedMonitor;

    long whenStarted = 0;

    private Thread gameThread = null; // vdom-engine-thread
    private int dieTries = 0; // How often we tried to kill the vdom-thread

    public void waitForJoin() {
        synchronized(hasJoinedMonitor) {
            long startTime = System.currentTimeMillis();
            while (!hasJoined ) {
                debug("Waiting for " + maxPause + " ms...");
                try {
                    hasJoinedMonitor.wait(maxPause);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                debug("Done waiting. hasJoined: " + (hasJoined?"True":"False"));
                if ((System.currentTimeMillis() - startTime) > maxPause) {
                    debug("Timed out waiting for player to join.");
                    break;
                }
            }
        }
    }
    public void playerJoined(){
        synchronized(hasJoinedMonitor) {
            hasJoined = true;
            hasJoinedMonitor.notify();
        }
    }

    public static void setVdomserver(VDomServer vdomserver) {
        RemotePlayer.vdomServer = vdomserver;
        maxPause = VDomServer.maxPause;
    }
    public static VDomServer getVdomserver() {
        return vdomServer;
    }
    public int getPort() {
        return myPort;
    }
    public boolean hasJoined() {
        return hasJoined;
    }

    public static MyCard makeMyCard(Card c, int index, boolean isBane, boolean isBlackMarket){
        MyCard card = new MyCard(index, c.getName(), c.getSafeName(), c.getName());
        card.desc = c.getDescription();
        card.expansion = c.getExpansion();
        card.originalExpansion = c.getExpansion();
        card.cost = c.getCost(null);
        card.costPotion = c.costPotion();
        card.isBane = isBane;
        card.isShelter = c.isShelter();
        card.isLooter = c.isLooter();
        card.isOverpay = c.isOverpay();
        if (c.equals(Cards.virtualRuins))
            card.isRuins = true;
        else
            card.isRuins = c.isRuins();


        card.pile = MyCard.SUPPLYPILE;

        if ((c.equals(Cards.bagOfGold)) ||
            (c.equals(Cards.diadem)) ||
            (c.equals(Cards.followers)) ||
            (c.equals(Cards.princess)) ||
            (c.equals(Cards.trustySteed))) {

            card.pile = MyCard.PRIZEPILE;
            card.isPrize = true;
        }

        if (c.equals(Cards.spoils) ||
            c.equals(Cards.mercenary) ||
            c.equals(Cards.madman))
        {
            card.pile = MyCard.NON_SUPPLY_PILE;
        }

        if (c.equals(Cards.necropolis) ||
            c.equals(Cards.overgrownEstate) ||
            c.equals(Cards.hovel))
        {
            card.pile = MyCard.SHELTER_PILES;
        }

        if ((c.equals(Cards.copper)) ||
            (c.equals(Cards.silver)) ||
            (c.equals(Cards.potion)) ||
            (c.equals(Cards.gold)) ||
            (c.equals(Cards.platinum))) card.pile = MyCard.MONEYPILE;

        if ((c.equals(Cards.estate)) ||
            (c.equals(Cards.duchy)) ||
            (c.equals(Cards.province)) ||
            (c.equals(Cards.colony)) ||
            (c.equals(Cards.curse))) card.pile = MyCard.VPPILE;

        if (Cards.ruinsCards.contains(c))
            card.pile = MyCard.RUINS_PILES;

        if (Cards.knightsCards.contains(c))
            card.pile = MyCard.KNIGHTS_PILES;

        if (c.equals(Cards.potion)) card.isPotion = true;
        if (c.equals(Cards.curse)) {
            card.isCurse = true;
            card.vp = ((CurseCard) c).getVictoryPoints();
        }
        if (c instanceof VictoryCard) {
            card.isVictory = true;
            card.vp = ((VictoryCard) c).getVictoryPoints();
        }
        if (c instanceof TreasureCard) {
            card.isTreasure = true;
            card.gold = ((TreasureCard) c).getValue();
        }
        if (c instanceof ActionCard) {
            ActionCard ac = (ActionCard) c;
            card.isAction = true;
            if (c instanceof DurationCard) {
                card.isDuration = true;
            } else
                if (ac.isAttack() || c.equals(Cards.virtualKnight))
                    card.isAttack = true;
        }
        if (Cards.isReaction(c))
            card.isReaction = true;
        if (isBlackMarket) {
            card.isBlackMarket = true;
            card.pile = MyCard.BLACKMARKET_PILE;
        }

        return card;
    }

    public Card intToCard(int i) {
        return cardsInPlay.get(i);
    }
    public Card[] intArrToCardArr(int[] cards) {
        Card[] cs = new Card[cards.length];
        for (int i = 0; i < cards.length; i++) {
            cs[i] = intToCard(cards[i]);
        }
        return cs;
    }
    public Card nameToCard(String o) {
        return intToCard(cardNamesInPlay.get(o));
    }
    @Override
    public int cardToInt(Card card) {
        // TODO:  NullPointerException for tournament prizes
        if (cardNamesInPlay.containsKey(card.getName()))
            return cardNamesInPlay.get(card.getName());
        else
            return -1;
    }

    public int[] cardArrToIntArr(Card[] cards) {
        int[] is = new int[cards.length];
        for (int i = 0; i < cards.length; i++) {
            is[i] = cardToInt(cards[i]);
        }
        return is;
    }

    public int[] arrayListToIntArr(ArrayList<Card> cards) {
        int[] is = new int[cards.size()];

        for (int i = 0; i < cards.size(); ++i) {
            is[i] = cardToInt((Card)cards.get(i));
        }

        return is;
    }

    public void setupCardsInPlay(MoveContext context) {
        ArrayList<MyCard> myCardsInPlayList = new ArrayList<MyCard>();

        int index = 0;

        // ensure card #0 is a card not to shade, e.g. Curse. See Rev r581
        Card curse = Cards.curse;
        MyCard mc = makeMyCard(curse, index, false, false);
        myCardsInPlayList.add(mc);
        cardNamesInPlay.put(curse.getName(), index);
        cardsInPlay.add(index, curse);
        index++;

        for (Card c : context.getCardsInGame()) {
            if (c.getSafeName().equals(Cards.curse.getSafeName()))
                continue;

            boolean isBlackMarket = false;
            for (Card bm : context.game.blackMarketPile) {
                if (c.getSafeName().equals(bm.getSafeName())) {
                    isBlackMarket = true;
                }
            }
            if (context.game.baneCard == null) {
                mc = makeMyCard(c, index, false, isBlackMarket);
            } else {
                mc = makeMyCard(c, index, c.getSafeName().equals(context.game.baneCard.getSafeName()), isBlackMarket);
            }
            myCardsInPlayList.add(mc);

            cardNamesInPlay.put(c.getName(), index);
            cardsInPlay.add(index, c);
            index++;
        }
        myCardsInPlay = myCardsInPlayList.toArray(new MyCard[0]);
    }

    public Event fullStatusPacket(MoveContext context, Player player, boolean isFinal) {
        if (player == null)
            player = context.getPlayer();

        int[] supplySizes = new int[cardsInPlay.size()];
        int[] embargos = new int[cardsInPlay.size()];
        int[] costs = new int[cardsInPlay.size()];

        int i_virtualRuins = -1;
        int i_virtualKnight = -1;
        int ruinsSize = 0;
        int knightSize = 0;

        for (int i = 0; i < cardsInPlay.size(); i++) {
            if (!isFinal)
            {
                supplySizes[i] = context.getCardsLeftInPile(intToCard(i));
            }
            else
            {
                supplySizes[i] = player.getMyCardCount(cardsInPlay.get(i));
                /* at end: count ruins and knights for each player */
                if (cardsInPlay.get(i).equals(Cards.virtualRuins))
                {
                   i_virtualRuins = i;
                }
                else if (cardsInPlay.get(i).isRuins())
                {
                   ruinsSize += player.getMyCardCount(cardsInPlay.get(i));
                }
                if (cardsInPlay.get(i).equals(Cards.virtualKnight))
                {
                   i_virtualKnight = i;
                }
                else if (cardsInPlay.get(i).isKnight())
                {
                   knightSize += player.getMyCardCount(cardsInPlay.get(i));
                }
            }
            embargos[i] = context.getEmbargos(intToCard(i));
            costs[i] = intToCard(i).getCost(context);
        }
        if (isFinal)
        {
            if(i_virtualRuins != -1)
            {
                supplySizes[i_virtualRuins] = ruinsSize;
            }
            if(i_virtualKnight != -1)
            {
                supplySizes[i_virtualKnight] = knightSize;
            }
        }

        // show opponent hand if possessed
        CardList shownHand = (player.isPossessed()) ? player.getHand() : getHand();

        // ArrayList<Card> playedCards = context.getPlayedCards();

        if (!allPlayers.contains(player))
            allPlayers.add(player);
        int numPlayers = allPlayers.size();

        int curPlayerIndex = allPlayers.indexOf(player);

        int numCards[] = new int[numPlayers];
        int turnCounts[] = new int[numPlayers];
        int deckSizes[] = new int[numPlayers];
        int discardSizes[] = new int[numPlayers];
        int handSizes[] = new int[numPlayers];
        int pirates[] = new int[numPlayers];
        int victoryTokens[] = new int[numPlayers];
        int guildsCoinTokens[] = new int[numPlayers];
        String realNames[] = new String[numPlayers];

        for (int i=0; i<numPlayers; i++) {
            Player p = allPlayers.get(i);
            if (!isFinal)
                handSizes[i] = p.getHand().size();
            else
                handSizes[i] = p.getVPs();
            turnCounts[i] = p.getTurnCount();
            deckSizes[i] = p.getDeckSize();
            discardSizes[i] = p.getDiscardSize();
            numCards[i] = p.getAllCards().size();

            pirates[i] = p.getPirateShipTreasure();
            victoryTokens[i] = p.getVictoryTokens();
            guildsCoinTokens[i] = p.getGuildsCoinTokenCount();
            realNames[i] = p.getPlayerName(false);
        }

        GameStatus gs = new GameStatus();

        int[] playedArray = new int[playedCards.size()];
        for (int i = 0; i < playedCards.size(); i++) {
            Card c = playedCards.get(i);
            boolean newcard = playedCardsNew.get(i).booleanValue();
            playedArray[i] = (cardToInt(c) * (newcard ? 1 : -1));
        }

        gs.setTurnStatus(new int[] {context.getActionsLeft(),
            context.getBuysLeft(),
                context.getCoinForStatus(),
                context.countThroneRoomsInEffect()
        })
        .setFinal(isFinal)
                .setPossessed(player.isPossessed())
                .setTurnCounts(turnCounts)
                .setSupplySizes(supplySizes)
                .setEmbargos(embargos)
                .setCosts(costs)
                .setHand(cardArrToIntArr(Game.sortCards ? shownHand.sort(new Util.CardHandComparator()) : shownHand.toArray()))
                .setPlayedCards(playedArray)
                .setCurPlayer(curPlayerIndex)
                .setCurName(player.getPlayerName(!isFinal && game.maskPlayerNames))
                .setRealNames(realNames)
                .setHandSizes(handSizes)
                .setDeckSizes(deckSizes)
                .setNumCards(numCards)
                .setPirates(pirates)
                .setVictoryTokens(victoryTokens)
                .setGuildsCoinTokens(guildsCoinTokens)
                .setCardCostModifier(context.cardCostModifier)
                .setPotions(context.getPotionsForStatus(player))
                .setPrince(cardArrToIntArr(player.getPrince().toArray()))
                .setIsland(cardArrToIntArr(player.getIsland().toArray()))
                .setVillage(player.equals(this) ? cardArrToIntArr(player.getNativeVillage().toArray()) : new int[0]/*show empty Village*/)
                .setBlackMarket(arrayListToIntArr(player.game.GetBlackMarketPile()))
                .setTrash(arrayListToIntArr(player.game.GetTrashPile()));

        Card topRuins = game.getTopRuinsCard();
        if (topRuins == null) {
            topRuins = Cards.virtualRuins;
        }
        gs.setRuinsTopCard(cardToInt(Cards.virtualRuins), topRuins);
        Card topKnight = game.getTopKnightCard();
        if (topKnight == null) {
            topKnight = Cards.virtualKnight;
        }
        gs.setKnightTopCard(cardToInt(Cards.virtualKnight), topKnight, topKnight.getCost(context), topKnight.isVictory(context));

        Event p = new Event(EType.STATUS)
                .setObject(new EventObject(gs));

        return p;
    }

    @Override
    public void newGame(MoveContext context) {
        hasJoinedMonitor = new Object(); // every game needs a different monitor, otherwise we wake up threads that are supposed to be dead.
        context.addGameListener(this);
        setupCardsInPlay(context);
        gameThread = Thread.currentThread();

        allPlayers.clear();
        myPort = connect();
        if (vdomServer != null)
            vdomServer.registerRemotePlayer(this);
        if (myPort == 0)
            quit("Could not create server.");
    }

    public Event sendWithAck(Event tosend, EType resp) throws IOException, NullPointerException {
        Event p;

        for (int i = 0; i < NUM_RETRIES; i++) {
            comm.put_ts(tosend);
            p = comm.get_ts();
            if (p == null)
                throw new IOException();
            else if (p.t == resp)
                return p;
        }

        throw new IOException();
    }

    @Override
    public void sendErrorHandler(Exception e) {
        e.printStackTrace();
        comm.injectNullReceived(); // This causes sendWithAck to receive a null and therefore throw an error, which we want.
    }

    private void achievement(MoveContext context, String achievement) {
        Event status = fullStatusPacket(curContext == null ? context : curContext, curPlayer, false).setString(achievement);
        try {
            sendWithAck(status.setType(EType.ACHIEVEMENT).setString(achievement), EType.Success);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Event query(MoveContext context, Event tosend, EType resp) {
        Event reply;
        for (int connections = 0; connections < NUM_RETRIES; connections++) {
            try {
                //sendWithAck(fullStatusPacket(context, null, false), EType.Success);
                comm.put_ts(fullStatusPacket(context, null, false));
                reply = sendWithAck(tosend, resp);
            } catch (IOException e) {
                reply = null;
            }
            if (reply != null)
                return reply;

            reconnect("Could not complete query.");
            waitForJoin();
            if (!hasJoined)
                quit("Response timed out");
        }
        quit("Could not complete query.");
        return null;
    }

    Player curPlayer = null;
    MoveContext curContext = null;
    boolean gameOver = false;

    @Override
    public void trash(Card card, Card responsible, MoveContext context) {
        //inform about trashed card
        Event p = new Event(EType.INFORM)
            .setString("TRASHED")
            .setCard(card);
        comm.put_ts(p);
        
        super.trash(card, responsible, context);
    }    
    
    @Override
    public void gameEvent(GameEvent event) {
        super.gameEvent(event);

        MoveContext context = event.getContext();

        // First we check for achievements
        checkForAchievements(context, event.getType());

        // Now we set up some variables that we need to send an event.
        boolean sendEvent = true;
        String playerName = "";
        boolean playerNameIncluded = false;
        if (event.getPlayer() != null && event.getPlayer().getPlayerName() != null) {
            playerName += event.getPlayer().getPlayerName() + ": ";
            playerNameIncluded = true;
        }
        boolean newTurn = false;
        boolean isFinal = false;
        Card[] cards = null;
        String playerInt = "" + allPlayers.indexOf(event.getPlayer());

        // Because we push all construction of strings to the client that talks to RemotePlayer, we
        // create the "extras" object that gives the client enough information to know what exactly
        // the event is.  This part of the "extras" object applies to all event types.
        List<Object> extras = new ArrayList<Object>();
        if (event.getPlayer().isPossessed()) {
            extras.add(event.getPlayer().controlPlayer.getPlayerName());
        } else {
            extras.add(null);
        }
        if (event.getAttackedPlayer() != null) {
            extras.add(event.getAttackedPlayer().getPlayerName());
        } else {
            extras.add(null);
        }
        if (context != null && context.getMessage() != null) {
            extras.add(context.getMessage());
        } else {
            extras.add(null);
        }


        // Now check for event-type-specific things that we should do.
        if (event.getType() == Type.VictoryPoints) {
                sendEvent = false;
        } else if (event.getType() == Type.GameStarting) {
            if (event.getPlayer() == this) {
                waitForJoin();
                if (!hasJoined)
                    quit("Join timed out");
            }
            whenStarted = System.currentTimeMillis();
            playedCards.clear();
            gameOver = false;

            // Only send the event if its the first game starting, which doesn't include the player
            // name, so that the "Chance for plat/colony" shows up only once and so that only one
            // GameStarting event gets shown in the status area.
            if (playerNameIncluded) {
                sendEvent = false;
            }
        } else if (event.getType() == Type.TurnBegin) {
            curPlayer = event.getPlayer();
            curContext = context;
            newTurn = true;
            playedCards.clear();
            playedCardsNew.clear();
        } else if (event.getType() == Type.TurnEnd) {
            playedCards.clear();
            playedCardsNew.clear();
            
            int islandSize = event.player.island.size();
            extras.add(islandSize);
            int nativeVillageSize = event.player.nativeVillage.size();
            extras.add(nativeVillageSize);
        } else if (event.getType() == Type.PlayingAction || event.getType() == Type.PlayingDurationAction) {
            playedCards.add(event.getCard());
            playedCardsNew.add(event.newCard);
        } else if (event.getType() == Type.PlayingCoin) {
            playedCards.add(event.getCard());
            playedCardsNew.add(event.newCard);
        } else if (event.getType() == Type.CardObtained) {
            if (event.responsible.equals(Cards.hornOfPlenty) && event.card instanceof VictoryCard) {
                int index = playedCards.indexOf(event.responsible);
                playedCards.remove(index);
                playedCardsNew.remove(index);
            }
        } else if (event.getType() == Type.GameOver) {
            curPlayer = event.getPlayer();
            curContext = context;
            isFinal = true;
            newTurn = true;
            Map<Object, Integer> counts = curPlayer.getVictoryCardCounts();
            extras.add(curPlayer.getPlayerName());
            extras.add(counts);
            extras.add(curPlayer.getVictoryPointTotals(counts));
            long duration = System.currentTimeMillis() - whenStarted;
            extras.add(duration);
            if (!event.getContext().cardsSpecifiedOnStartup()) {
                extras.add(event.getContext().getGameType());
            } else {
                extras.add(null);
            }
        } else if (event.getType() == Type.CantBuy) {
            cards = context.getCantBuy().toArray(new Card[0]);
        } else if (event.getType() == Type.GuildsTokenSpend) {
            if (event.getComment() != null) {
                extras.add(event.getComment());
            }
        } else if (event.getType() == Type.Status) {
            String coin = "" + context.getCoinAvailableForBuy();
            if(context.potions > 0)
                coin += "p";
            coin = "(" + coin + ")"; // <" + String.valueOf(event.player.discard.size()) + ">";
            extras.add("" + context.getActionsLeft());
            extras.add("" + context.getBuysLeft());
            extras.add(coin);
        }

        // We need to wait until this point to actually create the event, because the logic above
        // modified some of these variables.
        Event status = fullStatusPacket(curContext == null ? context : curContext, curPlayer, isFinal)
                .setGameEventType(event.getType())
                .setString(playerName)
                .setCard(event.getCard())
                .setBoolean(newTurn);
        status.o.os = extras.toArray();
        status.o.cs = cards;

        // Now we actually send the event.
        if (event.getPlayer() != null) {
            switch (event.getType()) {
                case BuyingCard:
                case CardObtained:
                    comm.put_ts(status.setType(EType.CARDOBTAINED).setString(playerInt).setInteger(cardToInt(event.getCard())));

                    break;
                case CardTrashed:
                    comm.put_ts(status.setType(EType.CARDTRASHED).setString(playerInt).setInteger(cardToInt(event.getCard())));

                    break;
                case CardRevealed:
                    comm.put_ts(status.setType(EType.CARDREVEALED).setString(playerInt).setInteger(cardToInt(event.getCard())));

                    break;
                case PlayerDefended:
                    comm.put_ts(status);
                    //comm.put_ts(status.setType(EType.CARDREVEALED).setString(playerInt).setInteger(cardToInt(event.getCard()))); /*causes error*/

                    break;
                default:
                    if(sendEvent)
                        comm.put_ts(status);
            }
            comm.put_ts(new Event(EType.SLEEP).setInteger(100));
        }
    }

    private void checkForAchievements(MoveContext context, Type eventType) {
        if (eventType == Type.GameOver) {
            int provinces = 0;
            int curses = 0;
            for(Card c : getAllCards()) {
                if(c.equals(Cards.province)) {
                    provinces++;
                }
                if(c.equals(Cards.curse)) {
                    curses++;
                }
            }
            if(provinces == 8 && Game.players.length == 2) {
                achievement(context, "2players8provinces");
            }
            if(provinces >= 10 && (Game.players.length == 3 || Game.players.length == 4)) {
                achievement(context, "3or4players10provinces");
            }
            int vp = this.getVPs();
            if(vp >= 100) {
                achievement(context, "score100");
                
                // without Prosperity?
                boolean prosperity = false;
                if (context.game.isColonyInGame() || context.game.isPlatInGame()) {
                    prosperity = true;
                }
                else {
                    Card[] cards = context.getCardsInGame();
                    for (Card card : cards) {
                        if (Cards.isSupplyCard(card) && card.getExpansion().equals("Prosperity")) {
                            prosperity = true;
                            break;
                        }
                    }
                }
                if (!prosperity) {
                    achievement(context, "score100withoutProsperity");
                }
            }
            if(vp >= 500) {
                achievement(context, "score500");
            }
            boolean beatBy50 = true;
            boolean skunk = false;
            boolean beatBy1 = false;
            boolean equalVp = false;
            boolean mostVp = true;
            for(Player opp : context.game.getPlayersInTurnOrder()) {
                if(opp != this) {
                    int oppVP = opp.getVPs();
                    if(oppVP > vp) {
                        mostVp = false;
                    }

                    if(oppVP <= 0) {
                        skunk = true;
                    }
                    if(vp == oppVP) {
                        equalVp = true;
                    }
                    if(vp == oppVP + 1) {
                        beatBy1 = true;
                    }
                    if(vp < oppVP + 50) {
                        beatBy50 = false;
                    }
                }
            }
            if(mostVp && beatBy50 && !equalVp && Game.numPlayers > 1) {
                achievement(context, "score50more");
            }
            if(mostVp && skunk) {
                achievement(context, "skunk");
            }
            if(mostVp && beatBy1 && !equalVp) {
                achievement(context, "score1more");
            }

            if(mostVp && !achievementSingleCardFailed) {
                achievement(context, "singlecard");
            }
            if(mostVp && curses == 13) {
                achievement(context, "13curses");
            }
            // no cards left in the Supply
            boolean allEmpty = true;
            Card[] cards = context.getCardsInGame();
            for (Card card : cards) {
                if (Cards.isSupplyCard(card) && !context.game.isPileEmpty(card)) {
                    allEmpty = false;
                    break;
                }
            }
            if(mostVp && allEmpty) {
                achievement(context, "allEmpty");
            }

        } else if (eventType == Type.TurnEnd) {
            if(context != null && context.getPlayer() == this && context.vpsGainedThisTurn > 30) {
                achievement(context, "gainmorethan30inaturn");
            }
            if(context != null && context.getPlayer() == this) {
                int tacticians = 0;
                for (int i = 0; i < context.player.nextTurnCards.size(); i++)
                {
                    if (context.player.nextTurnCards.get(i).equals(Cards.tactician))
                        tacticians++;
                }
                if (tacticians >= 2)
                    achievement(context, "2tacticians");
            }
        } else if (eventType == Type.OverpayForCard) {
            if (context != null && context.overpayAmount >= 10) {
                achievement(context, "overpayby10ormore");
            }
        } else if (eventType == Type.GuildsTokenObtained) {
            if (context != null && getGuildsCoinTokenCount() >= 50) {
                achievement(context, "stockpile50tokens");
            }
        } else if (eventType == Type.CardTrashed) {
            if(context != null && context.getPlayer() == this && context.cardsTrashedThisTurn > 5) {
                achievement(context, "trash5inaturn");
            }
        }
    }


    @Override
    public String getPlayerName() {
        return name;
    }

    @Override
    public String getPlayerName(boolean maskName) {
        return getPlayerName();
    }

    @Override
    protected Card[] pickCards(MoveContext context, SelectCardOptions sco, int count, boolean exact) {
        if (sco.allowedCards.size() == 0)
            return null;

        Event p = new Event(EType.GETCARD)
                .setInteger(count)
                .setBoolean(exact)
                .setObject(new EventObject(sco));

        p = query(context, p, EType.CARD);
        if (p == null)
            return null;
        else if (p.i == 0)
            return null;
        else if (p.i == 1 && p.o.is[0] == -1)
            // Hack to notify that "All" was selected
            return new Card[0];
        else
            return intArrToCardArr(p.o.is);
    }

    // If I were designing this from scratch, I may have picked an API that treated this
    // selectBoolean method and the selectOption method the same.  But I'm not designing this from
    // scratch, I'm just trying to cut the strings out of an existing API while minimizing my
    // effort, so we get something that's a little bit disjointed.  Oh well...

    @Override
    public boolean selectBoolean(MoveContext context, Card cardResponsible, Object[] extras) {
        Event p = new Event(EType.GETBOOLEAN)
                .setCard(cardResponsible)
                .setObject(new EventObject(extras));
        p = query(context, p, EType.BOOLEAN);
        if (p == null)
            return false;
        else
            return p.b;
    }

    @Override
    public int selectOption(MoveContext context, Card card, Object[] options) {
        /* choose from options */
        if (options.length == 1) {
            return 0;
        }

        Event p = new Event(EType.GETOPTION)
                .setCard(card)
                .setObject(new EventObject(options));
        p = query(context, p, EType.OPTION);
        if (p == null)
            return -1;
        else
            return p.i;
    }

    @Override
    protected int[] orderCards(MoveContext context, int[] cards) {
        if(cards != null && cards.length == 1) {
            return new int[]{ 0 };
        }

        Event p = new Event(EType.ORDERCARDS)
                .setObject(new EventObject(cards));

        p = query(context, p, EType.CARDORDER);
        if (p == null)
            return null;
        else
            return p.o.is;
    }

    @Override
    public boolean handle(Event e) {
        if (e.t == EType.CARDRANKING) {
            /*TODO frr*/
            //Goal: Sort cards by names in foreign language
            MyCard[] cardranking = e.o.ng.cards;
        }
        if (e.t == EType.HELLO) {
            name = (e.s == "" ? "Remote player" : e.s);
            debug("Name set: " + name);
            String[] players = new String[allPlayers.size()];
            for (Player p : allPlayers)
                players[allPlayers.indexOf(p)] = p.getPlayerName();

            //	try {
            comm.put_ts(new Event(EType.NEWGAME).setObject(new EventObject(new NewGame(myCardsInPlay, players))));
            playerJoined();
            //	} catch (Exception e1) {
            // TODO:Because put_ts is asynchronous, this will not work the way it was intended. Is that bad?
            // Probably not; if the connection is lost right after receiving a HELLO, we will notice soon enough.
            // Maybe we should implement synchronous sending though.
            //		debug("Could not send NEWGAME -- ignoring, but not setting hasJoined");
            //	}
            return true;
        }
        if (e.t == EType.SAY) {
            vdomServer.say(name + ": " + e.s);
            return true;
        }
        //		if (e.t == EType.DISCONNECT) {
        //			debug("Comms issued disconnect");
        //			comm.doWait(); // clear notification
        //			reconnect("Comms issued disconnect.");
        //		}
        return false;
    }

    private int connect() {
        int port = 0;
        hasJoined = false;
        for (int connections = 0; connections < NUM_RETRIES; connections++) {
            try {
                comm = new Comms(this, nextPort++);
                port = comm.getPort();
                System.out.println("Remote player now listening on port " + port);
                return port;
            } catch (IOException e) {
                // comm = null; // can cause NullPointerExceptions in different threads
                e.printStackTrace();
                debug ("Could not open a server for remote player... attempt " + (connections + 1));
            }
        }
        return port;
    }
    private void disconnect() {
        if (comm != null)
            comm.stop();
        // comm = null; // can cause NullPointerExceptions in different threads
        hasJoined = false;
        myPort = 0;
    }
    private void reconnect(String s) {
        if (vdomServer != null) {
            // TODO reconnect
            debug("Reconnecting... " + s);
            disconnect();
            myPort = connect();
            if (myPort == 0)
                quit(s + "; Could not recreate server");
        } else {
            quit(s);
        }
    }

    public void sendQuit() {
        // There was a string being sent here with QUIT, but it was ignored on the receiving side,
        // so I got rid of it to remove a dependency on ui/Strings.java.
        comm.put_ts(new Event(EType.QUIT));
        disconnect();
    }


    private void quit(String s) {
        debug("!!! Quitting: " + s + " !!!");
        if (vdomServer != null)
            vdomServer.endGame();
        else
            die();
    }

    private void die() {
        if (gameThread == null) {
            debug("die() called, but game thread already dead.");
            kill_game();
            return;
        }
        if (Thread.currentThread() == gameThread) {
            gameThread = null;
            throw new ExitException();
        } else {
            debug("die() called from outside vdom-thread");
            if (dieTries > 4) {
                debug("Could not kill vdom-thread");
                return;
            }
            dieTries++;
            kill_game();
        }
    }

    public void kill_game() {
        // Make main-thread throw an ExitException.
        vdomServer = null;
        playerJoined(); // Hack: Need thread to wake up
        if (comm != null) {
            comm.stop();
        } else {
            try
            {
                Thread.sleep(500); // HACK: wait for comm to be created
            } catch (InterruptedException e) { }
            if (comm != null) {
                comm.stop();
            } else {
                debug("Could not kill vdom thread");
            }
        }
    }
}
