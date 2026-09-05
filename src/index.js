/**
 * StreamCloud / CloudStream 3 Extension Entrypoint
 * Repository: https://github.com/ansrizal/anime
 * Author: Ans Rizal (@ansrizal)
 */

import sokujaProvider from "./providers/sokujaProvider.js";


// List of all active streaming providers in this repository
export const providers = [
  sokujaProvider,
  
];

// Named exports for modular access
export { sokujaProvider };


// Default export of provider catalog
export default providers;
