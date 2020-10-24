import SwaggerUI from 'swagger-ui'
import 'swagger-ui/dist/swagger-ui.css';

console.log('Using configuration:', swaggerConfig);

const ui = SwaggerUI({
  ...swaggerConfig,
  dom_id: '#swagger'
});

ui.initOAuth({
  appName: "Swagger UI for Cordaptor",
  clientId: 'implicit'
});
