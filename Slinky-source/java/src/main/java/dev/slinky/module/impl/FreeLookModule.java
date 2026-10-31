package dev.slinky.module.impl;

import dev.slinky.client.ClientContext;
import dev.slinky.client.SemanticEvent;
import dev.slinky.client.rotation.RotationService;
import dev.slinky.module.EvidenceModule;
import dev.slinky.module.ModuleCategory;

public final class FreeLookModule extends EvidenceModule {
   private boolean saved;
   private int perspective;
   private float yaw;
   private float pitch;

   public FreeLookModule() {
      super("freelook", "Free Look", ModuleCategory.RENDER);
   }

   @Override
   protected void onEnable(ClientContext context) {
      if (context.ready()) {
         this.saved = true;
         this.yaw = RotationService.storedRotationValid() ? RotationService.cameraYaw() : context.player().rotationYaw;
         this.pitch = RotationService.storedRotationValid() ? RotationService.cameraPitch() : context.player().rotationPitch;
         this.perspective = context.minecraft().gameSettings.thirdPersonView;
         context.minecraft().gameSettings.thirdPersonView = this.choice("target_perspective");
      }
   }

   @Override
   protected void onDisable(ClientContext context) {
      this.restore(context);
   }

   @Override
   public void onWorldUnload(ClientContext context) {
      this.restore(context);
   }

   private void restore(ClientContext context) {
      if (this.saved) {
         context.minecraft().gameSettings.thirdPersonView = this.perspective;
         RotationService.restoreCamera(this.yaw, this.pitch);
         this.saved = false;
      }
   }

   @Override
   public boolean listens(int type) {
      return type == 13;
   }

   @Override
   public void onEvent(ClientContext context, SemanticEvent event) {
      if (event.type == 13) {
         if (!this.saved && context.ready()) {
            this.onEnable(context);
         }

         if (this.saved) {
            event.active = true;
            event.yaw = this.yaw;
            event.pitch = this.pitch;
         }
      }
   }
}
