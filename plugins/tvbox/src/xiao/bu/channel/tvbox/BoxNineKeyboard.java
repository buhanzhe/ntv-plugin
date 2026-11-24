package xiao.bu.channel.tvbox;

import android.os.SystemClock;
import android.text.Editable;
import android.view.*;
import android.widget.*;

/** Remote-friendly nine-key multi-tap input, matching BL's 900ms letter cycling. */
final class BoxNineKeyboard extends LinearLayout {
    private static final String[] GROUPS={"1./","ABC2","DEF3","GHI4","JKL5","MNO6","PQRS7","TUV8","WXYZ9"};
    private final EditText input;
    private final Button[] digits=new Button[9];
    private int previous=-1,letter,cursor;
    private long pressed;
    private String last="";
    BoxNineKeyboard(BoxUi ui,EditText input){
        super(ui.context);this.input=input;setOrientation(VERTICAL);setPadding(0,ui.px(12),0,0);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)});
        TextView hint=ui.text("同一按键连按切换字母，稍停后输入下一个",18,BoxUi.SUB);addView(hint,new LayoutParams(-1,ui.px(32)));
        for(int row=0;row<3;row++){
            LinearLayout line=ui.horizontal();
            for(int column=0;column<3;column++){
                int index=row*3+column;String label=index==0?"1  ·  /":(index+1)+"  "+GROUPS[index].replaceAll("[0-9]","");
                digits[index]=key(ui,line,label,()->press(index));digits[index].setContentDescription("九键 "+(index+1)+" "+GROUPS[index]);
                digits[index].setOnKeyListener((view,code,event)->{
                    if(code==KeyEvent.KEYCODE_0||code==KeyEvent.KEYCODE_DEL||code==KeyEvent.KEYCODE_SPACE){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){if(code==KeyEvent.KEYCODE_DEL)erase();else insert(code==KeyEvent.KEYCODE_0?"0":" ");}return true;}
                    if(code<KeyEvent.KEYCODE_1||code>KeyEvent.KEYCODE_9)return false;
                    if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)press(code-KeyEvent.KEYCODE_1);return true;
                });
            }
            addView(line);
        }
        LinearLayout tools=ui.horizontal();key(ui,tools,"空格",()->insert(" "));key(ui,tools,"0",()->insert("0"));key(ui,tools,"删除",this::erase);key(ui,tools,"清空",()->{previous=-1;input.setText("");});addView(tools);
    }
    View entry(){return digits[0];}
    private Button key(BoxUi ui,LinearLayout row,String label,Runnable action){
        Button button=ui.button(label);button.setPadding(ui.px(6),0,ui.px(6),0);
        LayoutParams bounds=new LayoutParams(0,ui.px(66),1);bounds.setMargins(ui.px(6),ui.px(6),ui.px(6),ui.px(6));row.addView(button,bounds);button.setOnClickListener(view->action.run());return button;
    }
    private void press(int index){
        long now=SystemClock.uptimeMillis();int start=input.getSelectionStart(),end=input.getSelectionEnd();String group=GROUPS[index];
        boolean cycling=previous==index&&now-pressed<900&&start>0&&start==end&&start==cursor&&last.equals(input.getText().toString());
        if(cycling){letter=(letter+1)%group.length();input.getText().replace(start-1,start,group.substring(letter,letter+1));}
        else{letter=0;insert(group.substring(0,1));}
        previous=index;pressed=now;cursor=input.getSelectionStart();last=input.getText().toString();
    }
    private void insert(String value){
        previous=-1;Editable text=input.getText();int start=input.getSelectionStart(),end=input.getSelectionEnd();if(start<0||end<0)start=end=text.length();
        int left=Math.min(start,end),right=Math.max(start,end),remaining=text.length()-(right-left);text.replace(left,right,value);input.setSelection(left+text.length()-remaining);
    }
    private void erase(){int start=input.getSelectionStart(),end=input.getSelectionEnd();if(start==end&&start>0)input.setSelection(Character.offsetByCodePoints(input.getText(),start,-1),end);insert("");}
}
