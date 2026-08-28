/**
 * StreamCloud / CloudStream 3 Extension Entrypoint
 * Repository: https://github.com/ansrizal/anime
 * Author: Ans Rizal (@ansrizal)
 */

import ShokujaProvider from "./providers/ShokujaProvider.js";


// List of all active streaming providers in this repository
export const providers = [
  ShokujaProvider,
  
];

// Named exports for modular access
export { ShokujaProvider };


// Default export of provider catalog
export default providers;
