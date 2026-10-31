package dev.slinky.client;

import dev.slinky.module.Module;
import dev.slinky.module.ModuleManager;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

public final class BindingInput {
   private final byte[] keyBits = new byte[104];
   private final Map<Module, Boolean> requested = new IdentityHashMap<>();

   public boolean update(int nativeId, boolean down, boolean toggle) {
      return this.update(nativeId, down ? 1 : 0, toggle ? 1 : 0);
   }

   public boolean update(int nativeId, int requestedByte, int toggleByte) {
      if (nativeId >= 0 && nativeId < this.keyBits.length) {
         int old = this.keyBits[nativeId] & 255;
         int bit = old & 1;
         int requested = requestedByte & 0xFF;
         int next = requested;
         boolean unchanged = true;
         if ((toggleByte & 0xFF) != 1 || bit != requested) {
            if ((toggleByte & 0xFF) == 1) {
               next = (requested | old & 2) ^ 2;
            }

            this.keyBits[nativeId] = (byte)next;
            unchanged = bit == requested;
         }

         return unchanged;
      } else {
         throw new IllegalArgumentException("Binding ID outside the recovered key table");
      }
   }

   public boolean dispatch(ModuleManager manager, int nativeId, boolean down) {
      if (nativeId == 0) {
         return false;
      } else {
         boolean accepted = false;

         for (Module module : manager.all()) {
            if (module.getBinding() == nativeId) {
               if (module.isHoldBinding()) {
                  manager.requestEnabled(module, down);
                  this.requested.put(module, down);
                  accepted = true;
               } else if (down) {
                  manager.requestEnabled(module, !module.isEnabled());
               }

               accepted = true;
            }
         }

         return accepted;
      }
   }

   public boolean lastRequested(Module module) {
      return Boolean.TRUE.equals(this.requested.get(module));
   }

   public int bits(int nativeId) {
      return this.keyBits[nativeId] & 0xFF;
   }

   public void clear() {
      Arrays.fill(this.keyBits, (byte)0);
      this.requested.clear();
   }
}
