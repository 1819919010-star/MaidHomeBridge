package JumDa5he.maidhomebridge.client.platform;

import JumDa5he.maidhomebridge.client.BridgeClientEvents;
import JumDa5he.maidhomebridge.client.BridgeClientService;
import JumDa5he.maidhomebridge.network.BridgeNetwork;
import JumDa5he.maidhomebridge.platform.PlatformMenu;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.fml.ModList;
import java.util.*;
import java.util.function.*;

public final class PlatformScreen extends AbstractContainerScreen<PlatformMenu> {
    private static final BridgeClientService SERVICE=BridgeClientService.INSTANCE;
    private final JsonObject station=new JsonObject();
    private JsonObject state=new JsonObject();
    private final List<Control> controls=new ArrayList<>();
    private final List<Label> labels=new ArrayList<>();
    private int page, scroll, contentHeight, ticks, soundIndex, maidIndex;
    private boolean polling, migrate, importMode=true, includePlayers;
    private String host=SERVICE.endpointHost(), port=Integer.toString(SERVICE.endpointPort());
    private String first="",second="",houseName="MaidHome",listKind="maid",notice="";
    private String recordView="remote";
    private record Control(AbstractWidget widget,int y,BooleanSupplier enabled) {}
    private record Label(int y,Supplier<Component> text,int color) {}
    public PlatformScreen(PlatformMenu menu,Inventory inv,Component title) {
        super(menu,inv,title);
        station.addProperty("x",menu.pos.getX()); station.addProperty("y",menu.pos.getY()); station.addProperty("z",menu.pos.getZ());
        station.addProperty("station",menu.station.toString());
    }
    private static Component tr(String key,Object... args) { return Component.translatable("gui.maidhome_bridge."+key,args); }
    @Override protected void init() {
        imageWidth=Math.min(600,width-12); imageHeight=Math.min(410,height-12);
        super.init();
        if(minecraft.level!=null)station.addProperty("dimension",minecraft.level.dimension().location().toString());
        layout(); refresh();
    }
    private int w() { return imageWidth-24; }
    private void layout() {
        clearWidgets(); controls.clear(); labels.clear();
        int tabW=(imageWidth-24)/5;
        String[] names={"maid","house","sound","resources","connection"};
        for(int i=0;i<5;i++) { final int tab=i; Button b=Button.builder(tr(names[i]),v->{page=tab;scroll=0;layout();}).bounds(leftPos+12+i*tabW,topPos+49,tabW-3,20).build(); b.active=i!=page; addRenderableWidget(b); }
        addRenderableWidget(Button.builder(tr("help"),b->BridgeClientEvents.help()).bounds(leftPos+12,topPos+imageHeight-28,64,20).build());
        addRenderableWidget(Button.builder(tr("cancel"),b->{ if(ModList.get().isLoaded("minetomesh")) JumDa5he.maidhomebridge.client.house.HouseExporter.cancel(); SERVICE.cancel(); }).bounds(leftPos+80,topPos+imageHeight-28,110,20).build());
        addRenderableWidget(Button.builder(tr("close"),b->onClose()).bounds(leftPos+imageWidth-76,topPos+imageHeight-28,64,20).build());
        contentHeight=300;
        switch(page) { case 0->maids(); case 1->house(); case 2->sounds(); case 3->resources(); default->connection(); }
        positionControls();
    }
    private boolean idle() { return !SERVICE.busy() && !flag("active") && !flag("uncertain"); }
    private boolean eligible() { return state.has("maid") && flag("eligible") && idle(); }
    private boolean flag(String key) { return state.has(key)&&state.get(key).getAsBoolean(); }
    private String value(String key) { return BridgeNetwork.str(state,key); }
    private UUID target() { return UUID.fromString(value("maid")); }
    private void maids() {
        label(0,()->tr("target",value("name")),0xD7F7F6);
        label(14,()->tr("owner",value("owner")),0xB3CBD6);
        label(28,()->tr("model",value("model")),0xB3CBD6);
        label(43,()->{
            int count=state.has("count")?state.get("count").getAsInt():0;
            if(flag("uncertain"))return tr("uncertain");
            if(flag("active"))return SERVICE.ownsTask(station)?tr("own_task"):tr("occupied");
            if(count==0)return tr("no_maid"); if(count>1)return tr("many_maids");
            return flag("eligible")?tr("ready"):Component.literal(value("reason"));
        },0xF2CC86);
        button(0,62,145,migrate?"mode_move":"mode_copy",()->{migrate=!migrate;layout();},()->!SERVICE.busy());
        button(152,62,w()-152,"send_maid",()->{
            UUID maid=target(); confirm(migrate?"confirm_move":"confirm_copy",()->SERVICE.sendFromPlatform(station,maid,migrate));
        },this::eligible);
        label(87,()->tr(migrate?"move_hint":"copy_hint"),0xB3CBD6);
        label(106,()->tr("receive_title"),0xD7F7F6);
        button(0,125,145,importMode?"receive_import":"receive_save",()->{importMode=!importMode;layout();},()->!SERVICE.busy());
        button(152,125,w()-152,"wait",()->confirm(importMode?"confirm_receive_import":"confirm_receive_save",()->SERVICE.waitFromPlatform(station,importMode?"import":"save",true)),this::idle);
        button(0,151,145,"stop_wait",SERVICE::cancel,()->SERVICE.ownsTask(station)&&!SERVICE.waitingMode().isEmpty());
        button(152,151,w()-152,"process_received",()->confirm(importMode?"confirm_receive_import":"confirm_receive_save",()->SERVICE.waitFromPlatform(station,importMode?"import":"save",false)),()->idle()&&SERVICE.hasReceipt());
        button(0,177,145,"reject",()->SERVICE.waitFromPlatform(station,"reject",false),()->idle()&&SERVICE.hasReceipt());
        button(152,177,w()-152,"acknowledge",()->confirm("confirm_acknowledge",()->request("acknowledge",new JsonObject(),r->state=r)),()->flag("uncertain")&&!flag("active"));
        label(202,()->tr("receive_hint"),0xF2CC86);
        label(216,()->tr("archive_only"),0xB3CBD6);
        label(241,()->tr("advanced"),0xD7F7F6);
        button(0,260,145,"original_maids",SERVICE::maids,()->!SERVICE.busy());
        button(152,260,w()-152,"select_original",()->{
            var list=SERVICE.ownMaids(); if(!list.isEmpty()){ var maid=list.get(maidIndex++%list.size());SERVICE.select(maid.uuid()); notice=maid.name()+" / "+maid.modelId(); }
        },()->!SERVICE.busy()&&!SERVICE.ownMaids().isEmpty());
        label(286,()->tr("selection_hint"),0xB3CBD6);
        button(0,304,w(),"confirm_remove",()->{
            UUID maid=target();confirm("confirm_move",()->SERVICE.confirmRemovalFromPlatform(station,maid));
        },()->eligible()&&SERVICE.removalMatches(target()));
        label(330,()->Component.literal(notice),0xD7F7F6);
        contentHeight=360;
    }
    private void house() {
        boolean installed=ModList.get().isLoaded("minetomesh");
        label(0,()->tr(installed?"wand_hint":"missing_minetomesh"),0xF2CC86);
        label(16,()->flag("houseAllowed")?tr("house_allowed"):tr("house_denied"),0xB3CBD6);
        label(40,()->tr("house_name"),0xD7F7F6); edit(0,55,w(),houseName,v->houseName=v,64);
        label(82,()->tr("first_corner"),0xD7F7F6); edit(0,97,w(),first,v->first=v,48);
        label(124,()->tr("second_corner"),0xD7F7F6); edit(0,139,w(),second,v->second=v,48);
        label(165,()->tr("size",selectionSize()),0xB3CBD6);
        button(0,185,145,includePlayers?"players_yes":"players_no",()->{includePlayers=!includePlayers;layout();},()->installed&&idle());
        button(152,185,w()-152,"read_selection",()->request("selection",new JsonObject(),this::applySelection),()->installed&&!SERVICE.busy());
        button(0,211,145,"save_selection",()->{
            JsonObject data=new JsonObject();data.addProperty("first",first);data.addProperty("second",second);data.addProperty("includePlayers",includePlayers);
            request("selection",data,this::applySelection);
        },()->installed&&idle()&&flag("houseAllowed"));
        button(152,211,w()-152,"send_house",()->SERVICE.houseFromPlatform(station,houseName),()->installed&&idle()&&flag("houseAllowed")&&!houseName.isBlank());
        label(239,()->tr("house_hint"),0xB3CBD6);label(258,()->Component.literal(notice),0xF2CC86);contentHeight=295;
    }
    private String selectionSize() {
        try {
            String[] a=first.trim().split("[ ,，]+"),b=second.trim().split("[ ,，]+");
            return (Math.abs(Long.parseLong(a[0])-Long.parseLong(b[0]))+1)+" × "+(Math.abs(Long.parseLong(a[1])-Long.parseLong(b[1]))+1)+" × "+(Math.abs(Long.parseLong(a[2])-Long.parseLong(b[2]))+1);
        }catch(Exception e){return "—";}
    }
    private void applySelection(JsonObject value) {
        first=BridgeNetwork.str(value,"first");second=BridgeNetwork.str(value,"second");includePlayers=value.has("includePlayers")&&value.get("includePlayers").getAsBoolean();
        notice=tr("selection_loaded").getString();layout();
    }
    private String selectedSound() { var sounds=SERVICE.sounds(); return sounds.isEmpty()?"":sounds.get(Math.floorMod(soundIndex,sounds.size())); }
    private void sounds() {
        label(0,()->tr("local_sound",selectedSound()),0xD7F7F6);
        button(0,22,145,"next_sound",()->soundIndex++,()->!SERVICE.sounds().isEmpty());
        button(152,22,w()-152,"refresh_sound",()->SERVICE.list("sound"),()->SERVICE.connected()&&!SERVICE.busy());
        label(50,()->{
            for(var item:SERVICE.remoteList("sound")) if(item.isJsonObject()&&selectedSound().equals(BridgeNetwork.str(item.getAsJsonObject(),"id")))return tr("sound_exists");
            return tr("sound_not_verified");
        },0xF2CC86);
        button(0,76,w(),"send_sound",()->{String id=selectedSound();confirm("confirm_sound",()->SERVICE.soundFromPlatform(station,id));},()->idle()&&!selectedSound().isEmpty());
        label(106,()->tr("sound_independent"),0xB3CBD6);
        label(126,()->tr("advanced"),0xD7F7F6);
        button(0,148,w(),"original_sound",()->confirm("confirm_sound",()->SERVICE.originalSoundFromPlatform(station)),this::idle);
        label(176,()->tr("original_sound_hint"),0xB3CBD6);contentHeight=205;
    }
    private void resources() {
        int part=(w()-8)/3;
        for(int i=0;i<3;i++){String kind=new String[]{"maid","house","sound"}[i];button(i*(part+4),0,part,kind,()->{listKind=kind;recordView="remote";scroll=0;layout();},()->true);}
        button(0,28,110,"refresh",()->{recordView="remote";SERVICE.list(listKind);},()->SERVICE.connected()&&!SERVICE.busy());
        button(116,28,110,"records",()->{recordView="disk";SERVICE.readRecords();},()->true);
        button(232,28,Math.max(50,w()-232),"details",()->recordView="history",()->true);
        label(55,()->tr("list_hint"),0xB3CBD6);contentHeight=95;
    }
    private void connection() {
        label(0,()->tr("host"),0xD7F7F6);edit(0,17,w(),host,v->host=v,253);
        label(47,()->tr("port"),0xD7F7F6);edit(0,64,120,port,v->port=v,5);
        button(0,94,100,"connect",this::connect,()->!SERVICE.busy()&&!SERVICE.connected());
        button(106,94,100,"disconnect",SERVICE::disconnect,()->SERVICE.connected());
        button(212,94,Math.max(65,w()-212),"reconnect",()->{SERVICE.disconnect();connect();},()->!SERVICE.busy());
        button(0,120,100,"status",SERVICE::status,()->true);
        label(150,()->tr("endpoint_hint"),0xB3CBD6);
        label(173,()->Component.literal(notice),0xF2CC86);contentHeight=200;
    }
    private void connect() {
        try { int number=Integer.parseInt(port);if(number<1||number>65535||host.isBlank())throw new IllegalArgumentException();SERVICE.connect(host.trim(),number); }
        catch(Exception e){notice=tr("invalid_endpoint").getString();}
    }
    private void confirm(String key,Runnable action) {
        minecraft.setScreen(new ConfirmScreen(ok->{minecraft.setScreen(this);if(ok)action.run();},tr("confirmation"),tr(key),tr("confirm_action"),tr("back")));
    }
    private void request(String operation,JsonObject data,Consumer<JsonObject> callback) {
        JsonObject request=station.deepCopy();data.entrySet().forEach(e->request.add(e.getKey(),e.getValue()));
        BridgeNetwork.requestPlatform(operation,request).whenComplete((r,e)->minecraft.execute(()->{
            if(e!=null){Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();notice=String.valueOf(cause.getMessage());}
            else callback.accept(r);
        }));
    }
    private void refresh() {
        if(polling)return;polling=true;
        BridgeNetwork.requestPlatform("status",station.deepCopy()).whenComplete((r,e)->minecraft.execute(()->{
            polling=false;if(e==null)state=r;else{notice=tr("station_invalid").getString();state=new JsonObject();}
        }));
    }
    private void label(int y,Supplier<Component> text,int color) { labels.add(new Label(y,text,color)); }
    private void button(int x,int y,int width,String key,Runnable action,BooleanSupplier enabled) {
        Button b=Button.builder(tr(key),v->action.run()).bounds(leftPos+12+x,topPos+77+y,width,20).build();
        b.setTooltip(Tooltip.create(tr(key)));addRenderableWidget(b);controls.add(new Control(b,y,enabled));
    }
    private void edit(int x,int y,int width,String value,Consumer<String> change,int max) {
        EditBox box=new EditBox(font,leftPos+12+x,topPos+77+y,width,20,Component.empty());box.setMaxLength(max);box.setValue(value);box.setResponder(change);
        addRenderableWidget(box);controls.add(new Control(box,y,()->!SERVICE.busy()));
    }
    private int viewport() { return imageHeight-114; }
    private void positionControls() {
        for(Control c:controls){int y=topPos+77+c.y-scroll;c.widget.setY(y);c.widget.visible=y>=topPos+77&&y+20<=topPos+imageHeight-37;c.widget.active=c.enabled.getAsBoolean();}
    }
    @Override protected void containerTick() { super.containerTick();if(++ticks%20==0)refresh();positionControls(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(y>topPos+72&&y<topPos+imageHeight-34){scroll=Math.max(0,Math.min(Math.max(0,contentHeight-viewport()),scroll-(int)(vertical*22)));positionControls();return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y) {}
    @Override protected void renderBg(GuiGraphics g,float delta,int mx,int my) {
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xF0111D2B);
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+3,0xFF32C9C0);
        g.drawString(font,title,leftPos+12,topPos+9,0xF0FAFF,false);
        Component connection=tr(SERVICE.connected()?"connected":"disconnected",SERVICE.endpointHost()+":"+SERVICE.endpointPort());
        text(g,connection,leftPos+12,topPos+23,0xA9D6D9);
        text(g,Component.literal(SERVICE.taskStatus()),leftPos+12,topPos+36,0xF2CC86);
        g.fill(leftPos+8,topPos+73,leftPos+imageWidth-8,topPos+imageHeight-34,0xFF182A3A);
        g.enableScissor(leftPos+10,topPos+76,leftPos+imageWidth-10,topPos+imageHeight-35);
        for(Label l:labels)text(g,l.text.get(),leftPos+12,topPos+78+l.y-scroll,l.color);
        if(page==3) {
            var lines=new ArrayList<String>();
            if(recordView.equals("history")){lines.add(value("detail"));lines.addAll(SERVICE.history());}
            else if(recordView.equals("disk"))lines.addAll(SERVICE.records());
            else for(var item:SERVICE.remoteList(listKind))lines.add(item.toString());
            if(lines.isEmpty())lines.add(tr("no_results").getString());
            int y=80;
            for(String line:lines)for(var row:font.split(Component.literal(line),w()-8)){g.drawString(font,row,leftPos+12,topPos+78+y-scroll,0xC6D9E6,false);y+=12;}
            contentHeight=y+20;
        }
        g.disableScissor();
        if(contentHeight>viewport()) {
            int available=viewport(), thumb=Math.max(12,available*available/contentHeight);
            int y=topPos+77+scroll*(available-thumb)/Math.max(1,contentHeight-available);
            g.fill(leftPos+imageWidth-7,y,leftPos+imageWidth-4,y+thumb,0xFF32C9C0);
        }
    }
    private void text(GuiGraphics g,Component text,int x,int y,int color) { g.drawString(font,font.plainSubstrByWidth(text.getString(),w()),x,y,color,false); }
    @Override public void render(GuiGraphics g,int mx,int my,float delta) {
        super.render(g,mx,my,delta);renderTooltip(g,mx,my);
        if(mx>=leftPos+12&&mx<leftPos+imageWidth-12&&my>=topPos+77&&my<topPos+imageHeight-35)
            for(Label l:labels) {int y=topPos+78+l.y-scroll;if(my>=y&&my<y+10&&font.width(l.text.get())>w())g.renderTooltip(font,font.split(l.text.get(),Math.min(360,width-30)),mx,my);}
    }
}
