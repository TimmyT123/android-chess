package jwtc.android.chess;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jwtc.chess.*;
import jwtc.chess.board.*;


public class UI extends GameControl {

	public static final String TAG = "UI";

	// last mouse click position
	protected int m_iFrom;

	String engineValuePast;

	// searchthread message handling
	public static final int MSG_MOVE = 1;
	public static final int MSG_UCI_MOVE = 2;
	public static final int MSG_PLAY = 3;
	public static final int MSG_TEXT = 5;
	protected Handler m_searchThreadUpdateHandler = new Handler(){
        /** Gets called on every message that is received */
        // @Override
        public void handleMessage(Message msg) {
             
       	 	//Log.i(TAG, "searchThreadUpdateHandler ->" + msg.what);
       	 	// do move
	       	 if(msg.what == MSG_MOVE){
	       		 
	       		 enableControl();
	       		 int move = msg.getData().getInt("move");
	       		 if(move == 0){
	       			 setMessage("No move found");
	       		 } else
	       			 move(move, "", true);
	       		 
	       		 updateState();
	       		 //playNotification();
	       	 }
	       	 else if(msg.what == MSG_UCI_MOVE){
	       		enableControl();
	       		doUCIMove(msg.getData().getInt("from"), msg.getData().getInt("to"), msg.getData().getInt("promo"));
	       		//playNotification();
	       	 }
	       	 else if(msg.what == MSG_TEXT){
	       		 setEngineMessage(msg.getData().getString("text"));
	       	 }
	       	 else if(msg.what == MSG_PLAY){
	       		 play();
	       	 } 
	           
	       	 super.handleMessage(msg);
        }
   }; 

	
	
	public UI()
	{
		m_iFrom = -1;
		m_bActive = true;
	}
	
	public void start()
	{
		updateState();
	}
	
	public void newGame()
	{
		super.newGame();
		updateState();
	}
	public void undo()
	{
		super.undo();
		updateState();
	}

	protected boolean requestMove(int from, int to)
	{
		//Log.i("requestMove debug", m_game.getBoard().getPGNMoves(new ChessBoard()));
		if(_jni.isEnded() != 0)
			return false;
		
		if(_jni.requestMove(from, to) == 0)
		{
			setMessage(R.string.msg_illegal_move);
			return false;
		}
		
		addPGNEntry(_jni.getNumBoard()-1, _jni.getMyMoveToString(), "", _jni.getMyMove(), true);
		
		updateState();
		if(_jni.isEnded() == 0 && getPlayMode() == HUMAN_PC){
			play();
		}
		return true;
	}
	
	
	// in case of ambigious castle (Fischer random), this methodis called to handle the move
	protected void requestMoveCastle(int from, int to){
		if(_jni.isEnded() != 0)
			return;
		
		_jni.doCastleMove(from, to);
		addPGNEntry(_jni.getNumBoard()-1, _jni.getMyMoveToString(), "", _jni.getMyMove(), true);
		
		updateState();
		if(_jni.isEnded() == 0 && getPlayMode() == HUMAN_PC){
			play();
		}
	}
	
	protected void doUCIMove(int from, int to, int promo){
		if(promo > 0){
			_jni.setPromo(promo);
		}

		if(_jni.isEnded() != 0)
			return ;
		
		if(_jni.requestMove(from, to) == 0)
		{
			setMessage(R.string.msg_illegal_move); // UCI should make legal move
			return ;
		}
		
		addPGNEntry(_jni.getNumBoard()-1, _jni.getMyMoveToString(), "", _jni.getMyMove(), true);
		
		updateState();
	}
	
	@Override
	public void updateState()
	{
		super.updateState();
		paintBoard();
		
		 
		
		//if(m_choiceMode.getSelectedIndex() == HUMAN_HUMAN)
		//	setMessage(m_game.getBoard().getPGNMoves(m_game.getBoardRefurbish()));
		
		//co.pl(ChessBoard.bitbToString(m_game.getBoard().bitbAttacked()));
	}
	
	public int chessStateToR(int s){
		switch(s)
		{
		case ChessBoard.MATE: return R.string.state_mate;
		case ChessBoard.DRAW_MATERIAL: return R.string.state_draw_material; 
		case ChessBoard.CHECK: return R.string.state_check; 
		case ChessBoard.STALEMATE: return R.string.state_draw_stalemate; 
		case ChessBoard.DRAW_50: return R.string.state_draw_50; 
		case ChessBoard.DRAW_REPEAT: return R.string.state_draw_repeat;
		default: return R.string.state_play; 
		}
	}
	
	public void paintBoard(){
		setMessage("paint from UI");
	}

	// handle call from clickedEvent with parameters for the x and y coords of the point
	public boolean handleClick(int index)
	{
		//m_textStatus.setText("");
		if(false == m_bActive)
		{
			setMessage(R.string.msg_wait);
			return false;
		}
		
		if(m_iFrom == -1)
		{
			int turn = _jni.getTurn();
			if(_jni.pieceAt(turn, index) == BoardConstants.FIELD)
			{
				return false;
			}
			m_iFrom = index;
			paintBoard();
		}
		else
		{
			// test and make move if valid move
			boolean bValid = requestMove(m_iFrom, index);
			m_iFrom = -1;
			if(false == bValid){
				paintBoard();
				return false;
			}
		}	
		return true;
	}
		
	public int getPlayMode()
	{
		return HUMAN_PC; //m_choiceMode.getSelectedIndex();
	}

	@Override
	public void setMessage(String sMsg)
	{
	}
	@Override
	public void setEngineMessage(String sText)
	{
	}
	public void setMessage(int res){
		
	}

	@Override
	public void sendMessageFromThread(String sText)
	{
		Log.i(TAG, "sendMessageFromThread Jeroen engine");
		engineEvaluation(sText);

		Message m = new Message();
		Bundle b = new Bundle();
		m.what = MSG_TEXT;
		b.putString("text", sText);
		m.setData(b);
		m_searchThreadUpdateHandler.sendMessage(m);
	}

	public void engineEvaluation(String sText){
		Log.d(TAG, " engineEvaluation sText ->" + sText);

		//Engine will give a positive number (winning) on the side it is evaluating for, regardless of color

		Pattern pat = Pattern.compile("(\\w+) \\w+ .+\\s+(-?\\d+\\.\\d\\d)");  // gets 2nd move and engine value
		Matcher mat = pat.matcher(sText);

		while (mat.find()){
			String engineValue = mat.group(2);
			if (engineValuePast == null){engineValuePast = engineValue;}
			Float f_engineValueDiff = Float.valueOf(engineValue) - Float.valueOf(engineValuePast);

			if (f_engineValueDiff > 1){
				Log.d(TAG, "#### f_engineValueDiff ->" + f_engineValueDiff + " first move ->" + mat.group(1) + "  sText->" + sText);
			}
			Log.d(TAG, "f engValDiff ->" + f_engineValueDiff + "   engineValuePast ->" + engineValuePast + "   engineValueCurrent ->" + engineValue);
			engineValuePast = engineValue;
		}
	}

	@Override
	public void sendMoveMessageFromThread(int move){
		Message m = new Message();
		Bundle b = new Bundle();
		b.putInt("move", move);
		m.what = MSG_MOVE;
		m.setData(b);
		m_searchThreadUpdateHandler.sendMessage(m);
	}

	@Override
	public void sendUCIMoveMessageFromThread(int from, int to, int promo){
		Log.i(TAG, "sendUCIMoveMessageFromThread UCIEngine");
		Message m = new Message();
		Bundle b = new Bundle();
		b.putInt("from", from);
		b.putInt("to", to);
		b.putInt("promo", promo);
		m.what = MSG_UCI_MOVE;
		m.setData(b);
		m_searchThreadUpdateHandler.sendMessage(m);
	}
}
