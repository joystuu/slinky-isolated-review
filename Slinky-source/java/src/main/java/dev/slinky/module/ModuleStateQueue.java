package dev.slinky.module;

import java.util.ArrayList;
import java.util.List;

public final class ModuleStateQueue<T> {
   private final ModuleStateQueue.Lifecycle<T> lifecycle;
   private final List<T> enables = new ArrayList<>();
   private final List<T> disables = new ArrayList<>();

   public ModuleStateQueue(ModuleStateQueue.Lifecycle<T> lifecycle) {
      this.lifecycle = lifecycle;
   }

   public synchronized boolean request(T module, boolean enabled) {
      if (this.lifecycle.enabled(module) == enabled) {
         return true;
      } else if (!enabled && this.lifecycle.cancelDisable(module)) {
         return false;
      } else {
         (enabled ? this.enables : this.disables).add(module);
         return true;
      }
   }

   public synchronized void apply() {
      for (T module : this.enables) {
         this.lifecycle.mark(module, true);
      }

      for (int i = 0; i < this.enables.size(); i++) {
         this.lifecycle.notifyChange(this.enables.get(i), true);
      }

      this.enables.clear();
      this.lifecycle.beforeDisableBatch();

      for (T module : this.disables) {
         this.lifecycle.mark(module, false);
      }

      for (int i = 0; i < this.disables.size(); i++) {
         this.lifecycle.notifyChange(this.disables.get(i), false);
      }

      this.disables.clear();
   }

   public synchronized boolean pending() {
      return !this.enables.isEmpty() || !this.disables.isEmpty();
   }

   public synchronized void clear() {
      this.enables.clear();
      this.disables.clear();
   }

   public interface Lifecycle<T> {
      boolean enabled(T var1);

      boolean cancelDisable(T var1);

      void mark(T var1, boolean var2);

      void notifyChange(T var1, boolean var2);

      void beforeDisableBatch();
   }
}
