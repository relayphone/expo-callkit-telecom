import { requireNativeModule } from "expo";
import type { NativeTransport } from "./conversations/transport";
export default requireNativeModule<NativeTransport>("ExpoCallKitTelecom");
