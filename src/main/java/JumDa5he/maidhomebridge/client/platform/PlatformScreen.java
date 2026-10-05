package JumDa5he.maidhomebridge.client.platform;

import JumDa5he.maidhomebridge.client.BridgeClientService;
import JumDa5he.maidhomebridge.network.BridgeNetwork;
import JumDa5he.maidhomebridge.platform.PlatformMenu;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.fml.ModList;
import java.util.*;

/** Two primary actions. Service logs and tasks survive closing the screen. */
public final class PlatformScreen extends AbstractContainerScreen<PlatformMenu> {
    private static final BridgeClientService SERVICE=BridgeClientService.INSTANCE;
    private final JsonObject station=new JsonObject();
    private JsonObject state=new JsonObject();
    private Button send,receiver,reconnect,disconnect;
    private EditBox host,port;
    private int ticks,scroll;
    private boolean polling;
    private String lastMaid="";
    public PlatformScreen(PlatformMenu menu,Inventory inv,Component title) {
        super(menu,inv,title);station.addProperty("x",menu.pos.getX());station.addProperty("y",menu.pos.getY());station.addProperty("z",menu.pos.getZ());station.addProperty("station",menu.station.toString());
    }
    private static Component tr(String key,Object... args){return Component.translatable("gui.maidhome_bridge.simple."+key,args);}
    @Override protected void init() {
        imageWidth=Math.min(440,width-12);imageHeight=Math.min(330,height-12);super.init();
        station.addProperty("dimension",minecraft.level.dimension().location().toString());
        send=addRenderableWidget(Button.builder(tr("send"),b->chooseSend()).bounds(leftPos+16,topPos+56,(imageWidth-38)/2,24).build());
        receiver=addRenderableWidget(Button.builder(tr("enable"),b->toggleReceiver()).bounds(leftPos+22+(imageWidth-38)/2,topPos+56,(imageWidth-38)/2,24).build());
        String savedHost=host==null?SERVICE.endpointHost():host.getValue(),savedPort=port==null?Integer.toString(SERVICE.endpointPort()):port.getValue();
        host=addRenderableWidget(new EditBox(font,leftPos+16,topPos+100,imageWidth-102,20,tr("host")));host.setMaxLength(253);host.setValue(savedHost);
        host.setTooltip(Tooltip.create(tr("host_help")));
        port=addRenderableWidget(new EditBox(font,leftPos+imageWidth-78,topPos+100,62,20,tr("port")));port.setMaxLength(5);port.setFilter(v->v.matches("[0-9]*"));port.setValue(savedPort);
        disconnect=addRenderableWidget(Button.builder(tr("disconnect"),b->SERVICE.disconnect()).bounds(leftPos+16,topPos+126,(imageWidth-38)/2,20).build());
        reconnect=addRenderableWidget(Button.builder(tr("reconnect"),b->{var address=address();if(address!=null)SERVICE.reconnect(address.host(),address.port());}).bounds(leftPos+22+(imageWidth-38)/2,topPos+126,(imageWidth-38)/2,20).build());refresh();
    }
    private JumDa5he.maidhomebridge.client.EndpointSettings address() {
        try{return new JumDa5he.maidhomebridge.client.EndpointSettings(host.getValue(),Integer.parseInt(port.getValue()));}
        catch(NumberFormatException e){SERVICE.say("请填写有效端口，通常为 7411");return null;}
        catch(IllegalArgumentException e){SERVICE.say(e.getMessage());return null;}
    }
    private boolean applyAddress() {
        var address=address();if(address==null)return false;
        if(SERVICE.connected()&&(!address.host().equals(SERVICE.endpointHost())||address.port()!=SERVICE.endpointPort())){SERVICE.say("地址已修改，请先点击重连，再发送或启用接收端");return false;}
        try{SERVICE.configureEndpoint(address.host(),address.port());return true;}catch(java.io.IOException e){SERVICE.say("连接地址保存失败："+e.getMessage());return false;}
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if((host.isFocused()||port.isFocused())&&key!=256&&super.getFocused()!=null&&super.getFocused().keyPressed(key,scan,modifiers))return true;
        if((host.isFocused()||port.isFocused())&&key!=256)return false;
        return super.keyPressed(key,scan,modifiers);
    }
    private boolean flag(String key){return state.has(key)&&state.get(key).getAsBoolean();}
    private int count(){return state.has("count")?state.get("count").getAsInt():0;}
    private void chooseSend() {
        if(!applyAddress())return;
        if(flag("uncertain")){SERVICE.say("上次任务结果待确认，请核对两端并使用恢复指令处理");return;}
        boolean wand=ModList.get().isLoaded("minetomesh")&&java.util.stream.Stream.of(minecraft.player.getMainHandItem(),minecraft.player.getOffhandItem())
                .anyMatch(s->BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals("minetomesh:export_wand"));
        if(wand&&count()>0){minecraft.setScreen(new SendChoice());return;}
        if(wand){openHouse();return;}sendMaid();
    }
    private void sendMaid() {
        if(count()!=1){SERVICE.say(count()==0?"传输台上未检测到女仆":"台上有多名女仆，请只保留一名");return;}
        if(!flag("eligible")){SERVICE.say(BridgeNetwork.str(state,"reason"));return;}
        UUID maid=UUID.fromString(BridgeNetwork.str(state,"maid"));confirm("confirm_send",()->SERVICE.sendFromPlatform(station,maid,true));
    }
    private void toggleReceiver() {
        if(flag("receiver_here")){SERVICE.disableReceiver(station);return;}
        if(!applyAddress())return;
        JsonObject current=state.has("receiver")?state.getAsJsonObject("receiver"):new JsonObject();
        long revision=current.has("revision")?current.get("revision").getAsLong():0;
        if(current.has("enabled")&&current.get("enabled").getAsBoolean())confirm("replace",()->enable(revision,true));else enable(revision,false);
    }
    private void enable(long revision,boolean replace) {SERVICE.enableReceiver(station,revision,replace,r->{if(r.has("replace_required"))confirm("replace",()->enable(r.get("revision").getAsLong(),true));});}
    private void confirm(String key,Runnable action) {minecraft.setScreen(new ConfirmScreen(ok->{minecraft.setScreen(this);if(ok)action.run();},tr("confirm_title"),tr(key),tr("continue"),tr("back")));}
    private void openHouse() {
        if(!ModList.get().isLoaded("minetomesh")){SERVICE.say("发送房屋需要安装 MineToMesh");return;}
        BridgeNetwork.requestPlatform("selection",station).whenComplete((selection,error)->minecraft.execute(()->{
            if(error!=null){logError(error);return;}
            if(!selection.has("complete")||!selection.get("complete").getAsBoolean()){SERVICE.say("请先使用 MineToMesh 导出杖选择房屋的两个角");return;}
            minecraft.setScreen(new HouseSend(selection));
        }));
    }
    private void refresh() {
        if(polling)return;polling=true;
        BridgeNetwork.requestPlatform("status",station).whenComplete((result,error)->minecraft.execute(()->{
            polling=false;if(error!=null){state=new JsonObject();return;}state=result;
            String maid=BridgeNetwork.str(state,"maid");if(!maid.equals(lastMaid)){lastMaid=maid;if(!maid.isEmpty())SERVICE.say("检测到女仆："+BridgeNetwork.str(state,"name"));}
        }));
    }
    private void logError(Throwable e){while(e.getCause()!=null)e=e.getCause();SERVICE.say(String.valueOf(e.getMessage()));}
    @Override protected void containerTick(){super.containerTick();if(++ticks%20==0)refresh();send.active=!SERVICE.busy()&&!flag("active");receiver.active=!SERVICE.busy()&&!flag("active");reconnect.active=!SERVICE.busy();host.setEditable(!SERVICE.busy());port.setEditable(!SERVICE.busy());receiver.setMessage(tr(flag("receiver_here")?"disable":"enable"));}
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void renderLabels(GuiGraphics g,int x,int y){}
    @Override protected void renderBg(GuiGraphics g,float delta,int mx,int my) {
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xF0111D2B);g.fill(leftPos,topPos,leftPos+imageWidth,topPos+3,0xFF32C9C0);
        g.drawString(font,title,leftPos+16,topPos+10,0xF0FAFF,false);
        String status=flag("uncertain")?tr("uncertain").getString():SERVICE.busy()?SERVICE.taskStatus():flag("receiver_here")?tr(SERVICE.connected()?"waiting":"receiver_offline").getString():tr(SERVICE.connected()?"connected":"disconnected").getString();
        g.drawString(font,font.plainSubstrByWidth(status,imageWidth-32),leftPos+16,topPos+25,0xF2CC86,false);
        g.drawString(font,font.plainSubstrByWidth(tr("hint").getString(),imageWidth-32),leftPos+16,topPos+40,0xA9D6D9,false);
        g.drawString(font,tr("host"),leftPos+16,topPos+88,0xA9D6D9,false);
        g.drawString(font,tr("port"),leftPos+imageWidth-78,topPos+88,0xA9D6D9,false);
        g.drawString(font,tr("log"),leftPos+16,topPos+156,0xD7F7F6,false);
        int top=topPos+170,bottom=topPos+imageHeight-10,area=Math.max(12,bottom-top);g.fill(leftPos+12,top-3,leftPos+imageWidth-12,bottom,0xFF182A3A);
        var rows=new ArrayList<net.minecraft.util.FormattedCharSequence>();for(String line:SERVICE.history())rows.addAll(font.split(Component.literal(line),imageWidth-40));
        int max=Math.max(0,rows.size()*12-area);scroll=Math.min(scroll,max);int offset=max-scroll;
        g.enableScissor(leftPos+14,top,leftPos+imageWidth-14,bottom);for(int i=0;i<rows.size();i++)g.drawString(font,rows.get(i),leftPos+17,top+i*12-offset,0xC6D9E6,false);g.disableScissor();
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(y>=topPos+167){scroll=Math.max(0,scroll+(int)(vertical*24));return true;}return super.mouseScrolled(x,y,horizontal,vertical);}
    private final class SendChoice extends Screen {
        SendChoice(){super(tr("choose"));}
        @Override protected void init(){int x=width/2-90,y=height/2;addRenderableWidget(Button.builder(tr("maid"),b->{minecraft.setScreen(PlatformScreen.this);sendMaid();}).bounds(x,y-20,180,24).build());addRenderableWidget(Button.builder(tr("house"),b->openHouse()).bounds(x,y+10,180,24).build());}
        @Override public void render(GuiGraphics g,int x,int y,float delta){super.render(g,x,y,delta);g.drawCenteredString(font,title,width/2,height/2-45,0xFFFFFF);}
        @Override public void onClose(){minecraft.setScreen(PlatformScreen.this);}
    }
    private final class HouseSend extends Screen {
        private final JsonObject selection;private EditBox name;
        HouseSend(JsonObject selection){super(tr("house"));this.selection=selection;}
        @Override protected void init(){int x=width/2-125,y=height/2;name=addRenderableWidget(new EditBox(font,x,y-10,250,20,tr("house_name")));name.setMaxLength(64);name.setValue("MaidHome");addRenderableWidget(Button.builder(tr("send"),b->{String value=name.getValue().trim();if(value.isEmpty())return;minecraft.setScreen(PlatformScreen.this);SERVICE.houseFromPlatform(station,value);}).bounds(x,y+22,122,24).build());addRenderableWidget(Button.builder(tr("back"),b->onClose()).bounds(x+128,y+22,122,24).build());}
        @Override public void render(GuiGraphics g,int x,int y,float delta){super.render(g,x,y,delta);g.drawCenteredString(font,title,width/2,height/2-65,0xFFFFFF);g.drawCenteredString(font,BridgeNetwork.str(selection,"first")+" → "+BridgeNetwork.str(selection,"second"),width/2,height/2-48,0xA9D6D9);g.drawCenteredString(font,tr("house_name"),width/2,height/2-28,0xFFFFFF);}
        @Override public void onClose(){minecraft.setScreen(PlatformScreen.this);}
    }
}
