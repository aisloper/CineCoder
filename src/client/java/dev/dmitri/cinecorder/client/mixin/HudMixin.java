package dev.dmitri.cinecorder.client.mixin;

import dev.dmitri.cinecorder.entity.CameraRigEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the HUD (and with it the first-person hand) while a player is inside a cinematic camera. */
@Mixin(Hud.class)
public class HudMixin {
	@Shadow
	private boolean isHidden;

	@Unique
	private boolean cinecorder$overridden;

	@Unique
	private boolean cinecorder$original;

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void cinecorder$hideDuringPlayback(CallbackInfo info) {
		boolean cinematic = Minecraft.getInstance().getCameraEntity() instanceof CameraRigEntity;

		if (cinematic && !this.cinecorder$overridden) {
			this.cinecorder$original = this.isHidden;
			this.isHidden = true;
			this.cinecorder$overridden = true;
		} else if (!cinematic && this.cinecorder$overridden) {
			this.isHidden = this.cinecorder$original;
			this.cinecorder$overridden = false;
		}
	}
}
