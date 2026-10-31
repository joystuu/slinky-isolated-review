package dev.slinky.module.impl;

import dev.slinky.client.ClientContext;
import dev.slinky.client.GuiModuleList;
import dev.slinky.client.GuiModuleSettings;
import dev.slinky.client.SemanticEvent;
import dev.slinky.client.render.ProfileServices;
import dev.slinky.client.render.RecoveredGui;
import dev.slinky.client.render.RecoveredGuiScreen;
import dev.slinky.client.render.RenderAccess;
import dev.slinky.client.render.RenderConstants;
import dev.slinky.client.render.RenderEventPayload;
import dev.slinky.client.render.RenderModuleAccess;
import dev.slinky.module.EvidenceModule;
import dev.slinky.module.ModuleCategory;
import dev.slinky.module.ModuleManager;
import dev.slinky.setting.ColorSetting;
import java.io.IOException;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

public final class GuiModule extends EvidenceModule {
   private final ModuleManager manager;
   private final RecoveredGui.Runtime runtime = new RecoveredGui.Runtime();
   private boolean shutdown;

   public GuiModule(ModuleManager manager) {
      super("gui", "GUI", ModuleCategory.RENDER);
      this.manager = manager;
      RenderModuleAccess.bindGuiInteraction(this::interactionRequested);
   }

   public RecoveredGui.Runtime runtime() {
      return this.runtime;
   }

   public float scale() {
      return RenderConstants.f(1381616 + this.choice("scale") * 4);
   }

   public ColorSetting.Value accent() {
      return this.color("accent_color");
   }

   public boolean compact() {
      return this.flag("compact");
   }

   public boolean wide() {
      return this.flag("wide");
   }

   public boolean interactionRequested() {
      return this.runtime.closeRequested;
   }

   public int redrawFlags(int old) {
      return old | 3;
   }

   @Override
   public boolean listens(int t) {
      return t == 0 || t == 5 || t == 9 || t == 10 || t == 26;
   }

   @Override
   protected void onEnable(ClientContext c) {
      this.runtime.start(RenderAccess.now());
      if (Mouse.isGrabbed()) {
         Mouse.setCursorPosition(c.minecraft().displayWidth / 2, c.minecraft().displayHeight / 2);
         Mouse.setGrabbed(false);
      }

      if (!(c.minecraft().currentScreen instanceof RecoveredGuiScreen)
         && !(c.minecraft().currentScreen instanceof GuiModuleList)
         && !(c.minecraft().currentScreen instanceof GuiModuleSettings)) {
         c.minecraft().displayGuiScreen(new RecoveredGuiScreen(this.manager, this));
      }
   }

   @Override
   protected void onDisable(ClientContext c) {
      this.runtime.finishModal(true);
      this.runtime.clearInteraction();
      GuiScreen screen = c.minecraft().currentScreen;
      if (screen instanceof RecoveredGuiScreen || screen instanceof GuiModuleList || screen instanceof GuiModuleSettings) {
         c.minecraft().displayGuiScreen(null);
      }

      if (c.ready() && !this.shutdown) {
         c.minecraft().setIngameFocus();
      }

      this.saveOnClose();
      RenderAccess.releaseCanvasResources();
   }

   public void saveOnClose() {
      if (!this.shutdown) {
         try {
            this.manager.input().profiles().saveConfiguration();
         } catch (IOException var8) {
            SemanticEvent error = new SemanticEvent(12);
            RenderEventPayload payload = RenderEventPayload.attach(error);
            payload.errorCode = var8 instanceof ProfileServices.Failure ? ((ProfileServices.Failure)var8).code : 1;

            try {
               this.manager.dispatch(error);
            } finally {
               RenderEventPayload.detach(error);
            }
         }
      }
   }

   public long dispatchInput(int kind, int key, boolean pressed, int unchanged, byte[] utf8, int x, int y, float wheel) {
      if (this.isEnabled() && this.runtime.input(kind, key, x, y, wheel, pressed, utf8)) {
         return 1L;
      } else {
         if (kind == 2 && pressed && key != 0) {
            if (unchanged == 0 && key == this.getBinding()) {
               this.manager.requestEnabled(this, !this.isEnabled());
               return 1L;
            }

            if (key == 87 && this.isEnabled()) {
               this.manager.requestEnabled(this, false);
               return 1L;
            }
         }

         return this.isEnabled() && (kind != 2 || !this.flag("allow_input") || Integer.compareUnsigned(key, 5) <= 0) ? (long)kind & 4294967040L | 1L : 0L;
      }
   }

   public void screenClosed() {
      if (this.isEnabled()) {
         this.manager.requestEnabled(this, false);
      }
   }

   @Override
   public void onEvent(ClientContext c, SemanticEvent e) {
      RenderEventPayload payload = RenderEventPayload.get(e);
      if (e.type == 9 && payload != null) {
         payload.redrawFlags = this.redrawFlags(payload.redrawFlags);
      } else if (e.type == 10 && payload != null && payload.guiDraw != null) {
         payload.guiDraw.run();
      } else if (e.type == 26) {
         this.shutdown = true;
         RenderAccess.releaseCanvasResources();
         this.runtime.finishModal(true);
         this.runtime.clearInteraction();
      } else if (e.type == 0 && Mouse.isGrabbed()) {
         Mouse.setGrabbed(false);
      }
   }

   @Override
   public void onWorldUnload(ClientContext c) {
      this.runtime.finishModal(true);
      this.runtime.clearInteraction();
      RenderAccess.releaseCanvasResources();
   }
}
